using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace MihonPcWorker
{
    public class NeedsRefreshException : Exception
    {
        public NeedsRefreshException(string message) : base(message) { }
    }

    public class DownloadEngine
    {
        private readonly WorkerConfig _config;
        private readonly string _jobsFilePath;
        private readonly ConcurrentDictionary<string, JobRecord> _jobs = new();
        private readonly SemaphoreSlim _throttle;
        private readonly HttpClient _httpClient;
        private static readonly JsonSerializerOptions JsonOpts = new() { WriteIndented = true };

        private static readonly HashSet<string> ActiveStates = new(StringComparer.OrdinalIgnoreCase)
        {
            "queued", "downloading", "paused", "needs_refresh"
        };

        private static readonly HashSet<string> TerminalStates = new(StringComparer.OrdinalIgnoreCase)
        {
            "completed", "failed", "cancelled"
        };

        public DownloadEngine(WorkerConfig config, string baseDirectory)
        {
            _config = config;
            _jobsFilePath = Path.Combine(baseDirectory, "jobs-v2.json");
            _throttle = new SemaphoreSlim(Math.Clamp(config.Concurrency, 1, 6));

            var handler = new SocketsHttpHandler
            {
                AutomaticDecompression = DecompressionMethods.All,
                PooledConnectionLifetime = TimeSpan.FromMinutes(15),
                PooledConnectionIdleTimeout = TimeSpan.FromMinutes(2),
                MaxConnectionsPerServer = 10,
                EnableMultipleHttp2Connections = true
            };

            _httpClient = new HttpClient(handler)
            {
                Timeout = TimeSpan.FromSeconds(90)
            };
            _httpClient.DefaultRequestHeaders.UserAgent.ParseAdd("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36");

            RestoreJobs();
            CleanupHistory();
            ResumeQueuedJobs();
        }

        public IReadOnlyCollection<JobRecord> GetAllJobs() => _jobs.Values.ToList();

        public JobRecord? GetJob(string jobId)
        {
            _jobs.TryGetValue(jobId, out var job);
            return job;
        }

        public int GetActiveJobsCount() => _jobs.Values.Count(j => ActiveStates.Contains(j.State.Status));

        public int GetTotalJobsCount() => _jobs.Count;

        public JobRecord Enqueue(JobCreateRequest request)
        {
            var id = string.IsNullOrWhiteSpace(request.JobId) ? Guid.NewGuid().ToString() : request.JobId;
            request.JobId = id;

            if (_jobs.ContainsKey(id))
            {
                throw new InvalidOperationException("Job already exists");
            }

            if (request.Pages == null || request.Pages.Count == 0)
            {
                throw new ArgumentException("Empty page list in job");
            }

            var title = string.IsNullOrWhiteSpace(request.ChapterTitle) ? "Chapter" : request.ChapterTitle;
            var state = new JobState
            {
                JobId = id,
                Title = title,
                Status = "queued",
                Total = request.Pages.Count,
                UpdatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
            };

            var record = new JobRecord(request, state);
            _jobs[id] = record;
            PersistJobs();

            LogMessage(ConsoleColor.Cyan, $"[Queue] {record.Request.MangaTitle} - {record.Request.ChapterTitle} ({state.Total} pages)");

            _ = Task.Run(() => ExecuteJobAsync(record));
            return record;
        }

        public bool RefreshJob(string id, List<PageItem> pages)
        {
            if (!_jobs.TryGetValue(id, out var record)) return false;

            record.Request.Pages = pages;
            record.State.Status = "queued";
            record.State.Error = null;
            record.State.Completed = 0;
            record.State.Total = pages.Count;
            record.State.Cancelled = false;
            record.State.Paused = false;
            record.State.UpdatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

            PersistJobs();
            LogMessage(ConsoleColor.Yellow, $"[Refresh] URLs refreshed for: {record.State.Title}");

            _ = Task.Run(() => ExecuteJobAsync(record));
            return true;
        }

        public bool ControlJob(string id, string action)
        {
            if (!_jobs.TryGetValue(id, out var record)) return false;
            ApplyAction(record, action);
            return true;
        }

        public int ControlAll(string action)
        {
            int count = 0;
            foreach (var record in _jobs.Values)
            {
                bool applicable = action switch
                {
                    "pause" => record.State.Status == "downloading" || record.State.Status == "queued",
                    "resume" => record.State.Status == "paused",
                    "cancel" => ActiveStates.Contains(record.State.Status),
                    "retry" => record.State.Status == "failed" || record.State.Status == "cancelled" || record.State.Status == "needs_refresh",
                    _ => false
                };

                if (applicable)
                {
                    ApplyAction(record, action);
                    count++;
                }
            }
            return count;
        }

        private void ApplyAction(JobRecord record, string action)
        {
            switch (action.ToLowerInvariant())
            {
                case "pause":
                    record.State.Paused = true;
                    record.State.Status = "paused";
                    LogMessage(ConsoleColor.Yellow, $"[Paused] {record.State.Title}");
                    break;

                case "resume":
                    record.State.Paused = false;
                    record.State.Status = "queued";
                    LogMessage(ConsoleColor.Cyan, $"[Resumed] {record.State.Title}");
                    _ = Task.Run(() => ExecuteJobAsync(record));
                    break;

                case "cancel":
                    record.State.Cancelled = true;
                    record.State.Status = "cancelled";
                    record.State.Error = "Cancelled by user";
                    record.Cts?.Cancel();
                    LogMessage(ConsoleColor.Red, $"[Cancelled] {record.State.Title}");
                    break;

                case "retry":
                    record.State.Cancelled = false;
                    record.State.Paused = false;
                    record.State.Error = null;
                    record.State.Status = "queued";
                    LogMessage(ConsoleColor.Cyan, $"[Retrying] {record.State.Title}");
                    _ = Task.Run(() => ExecuteJobAsync(record));
                    break;
            }

            record.State.UpdatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            PersistJobs();
        }

        private async Task ExecuteJobAsync(JobRecord record)
        {
            var state = record.State;
            var req = record.Request;

            await _throttle.WaitAsync();
            record.Cts = new CancellationTokenSource();
            var cancelToken = record.Cts.Token;

            try
            {
                if (state.Cancelled) throw new OperationCanceledException();

                state.Status = "downloading";
                if (state.StartedAt == 0)
                {
                    state.StartedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                }
                state.UpdatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                PersistJobs();

                var downloadsDir = _config.GetDownloadsDir();
                if (string.IsNullOrEmpty(downloadsDir))
                {
                    throw new IOException("Worker storage folder has not been set yet. Please set it in Mihon or Worker console.");
                }
                var safeSource = SafeName(req.SourceDir);
                var safeManga = SafeName(req.MangaDir);
                var safeChapter = SafeName(req.ChapterDir);

                var mangaDir = Path.Combine(downloadsDir, safeSource, safeManga);
                Directory.CreateDirectory(mangaDir);

                var tempDirName = $".{safeChapter}.{state.JobId[..Math.Min(8, state.JobId.Length)]}.downloading";
                var tempDir = Path.Combine(mangaDir, tempDirName);
                Directory.CreateDirectory(tempDir);

                var pages = req.Pages;
                state.Total = pages.Count;
                int digits = Math.Max(3, pages.Count.ToString().Length);

                LogMessage(ConsoleColor.White, $"[Downloading] {req.MangaTitle} - {req.ChapterTitle} ({pages.Count} pages)");

                for (int i = 0; i < pages.Count; i++)
                {
                    while (state.Paused && !state.Cancelled)
                    {
                        await Task.Delay(500, cancelToken);
                    }
                    if (state.Cancelled) throw new OperationCanceledException();

                    EnsureStorageThreshold();

                    var page = pages[i];
                    var basename = page.Index.ToString().PadLeft(digits, '0');

                    // Check if page already exists and valid in tempDir
                    var existing = Directory.GetFiles(tempDir, $"{basename}.*")
                        .FirstOrDefault(f => new FileInfo(f).Length >= 128);

                    if (existing == null)
                    {
                        long bytes = await DownloadPageWithRetryAsync(tempDir, page, basename, cancelToken);
                        state.BytesDownloaded += bytes;
                    }

                    state.Completed = i + 1;
                    state.UpdatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

                    if ((i + 1) % 5 == 0 || i + 1 == pages.Count)
                    {
                        PersistJobs();
                        Console.Write($"\r[Progress] {state.Title}: {state.Completed}/{state.Total} ({FormatBytes(state.BytesDownloaded)})   ");
                    }
                }
                Console.WriteLine();

                // Validate chapter
                ValidateChapter(tempDir, pages.Count);

                // Output packaging
                var outputMode = (req.OutputMode ?? (_config.DefaultCbz ? "cbz" : "folder")).ToLowerInvariant();
                if (outputMode == "cbz")
                {
                    FinishCbz(mangaDir, tempDir, safeChapter, state.JobId);
                }
                else
                {
                    FinishFolder(mangaDir, tempDir, safeChapter);
                }

                state.Status = "completed";
                state.Error = null;
                state.UpdatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                PersistJobs();

                LogMessage(ConsoleColor.Green, $"[Completed] {req.MangaTitle} - {req.ChapterTitle} ({outputMode.ToUpper()})");
            }
            catch (NeedsRefreshException nre)
            {
                state.Status = "needs_refresh";
                state.Error = nre.Message;
                state.UpdatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                PersistJobs();
                LogMessage(ConsoleColor.Yellow, $"[Needs Refresh] {state.Title}: {nre.Message}");
            }
            catch (OperationCanceledException)
            {
                state.Status = "cancelled";
                state.Error = "Cancelled by user";
                state.UpdatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                PersistJobs();
                LogMessage(ConsoleColor.DarkYellow, $"[Cancelled] {state.Title}");
            }
            catch (Exception ex)
            {
                state.Status = "failed";
                state.Error = ex.Message;
                state.UpdatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                PersistJobs();
                LogMessage(ConsoleColor.Red, $"[Failed] {state.Title}: {ex.Message}");
            }
            finally
            {
                record.Cts?.Dispose();
                record.Cts = null;
                _throttle.Release();
            }
        }

        private async Task<long> DownloadPageWithRetryAsync(string tempDir, PageItem page, string basename, CancellationToken ct)
        {
            Exception? lastEx = null;
            for (int attempt = 0; attempt < 3; attempt++)
            {
                ct.ThrowIfCancellationRequested();
                try
                {
                    return await DownloadPageAsync(tempDir, page, basename, ct);
                }
                catch (NeedsRefreshException)
                {
                    throw;
                }
                catch (HttpRequestException hre) when (hre.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden or HttpStatusCode.Gone or HttpStatusCode.NotFound)
                {
                    throw new NeedsRefreshException($"Page {page.Index}: HTTP {(int)hre.StatusCode!}");
                }
                catch (Exception ex)
                {
                    lastEx = ex;
                    await Task.Delay(1000 * (1 << attempt), ct);
                }
            }
            throw new IOException($"Page {page.Index} download failed after 3 attempts: {lastEx?.Message}", lastEx);
        }

        private async Task<long> DownloadPageAsync(string tempDir, PageItem page, string basename, CancellationToken ct)
        {
            using var reqMsg = new HttpRequestMessage(HttpMethod.Get, page.Url);

            if (page.Headers != null)
            {
                foreach (var (k, v) in page.Headers)
                {
                    if (string.IsNullOrWhiteSpace(k) || string.IsNullOrWhiteSpace(v)) continue;
                    if (k.Equals("User-Agent", StringComparison.OrdinalIgnoreCase))
                    {
                        reqMsg.Headers.UserAgent.Clear();
                        reqMsg.Headers.TryAddWithoutValidation("User-Agent", v);
                    }
                    else if (k.Equals("Referer", StringComparison.OrdinalIgnoreCase))
                    {
                        reqMsg.Headers.Referrer = new Uri(v);
                    }
                    else
                    {
                        reqMsg.Headers.TryAddWithoutValidation(k, v);
                    }
                }
            }

            using var resp = await _httpClient.SendAsync(reqMsg, HttpCompletionOption.ResponseHeadersRead, ct);

            if (resp.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden or HttpStatusCode.Gone or HttpStatusCode.NotFound)
            {
                throw new NeedsRefreshException($"Page {page.Index}: HTTP {(int)resp.StatusCode}");
            }

            resp.EnsureSuccessStatusCode();

            var mime = resp.Content.Headers.ContentType?.MediaType;
            var ext = GetImageExtension(mime, page.Url);
            var filePath = Path.Combine(tempDir, basename + ext);
            var partPath = filePath + ".part";

            if (File.Exists(partPath)) File.Delete(partPath);

            await using (var netStream = await resp.Content.ReadAsStreamAsync(ct))
            await using (var fileStream = new FileStream(partPath, FileMode.Create, FileAccess.Write, FileShare.None, 64 * 1024, useAsync: true))
            {
                await netStream.CopyToAsync(fileStream, ct);
            }

            ValidateImage(partPath, mime);

            if (File.Exists(filePath)) File.Delete(filePath);
            File.Move(partPath, filePath);

            return new FileInfo(filePath).Length;
        }

        private static void ValidateImage(string filePath, string? mime)
        {
            var info = new FileInfo(filePath);
            if (info.Length < 128)
            {
                try { File.Delete(filePath); } catch { }
                throw new IOException("Downloaded image is empty or truncated (<128 bytes)");
            }

            byte[] header = new byte[12];
            using (var fs = File.OpenRead(filePath))
            {
                int read = fs.Read(header, 0, header.Length);
                if (read < 4)
                {
                    try { File.Delete(filePath); } catch { }
                    throw new IOException("Image file header is too small");
                }
            }

            bool isJpeg = header[0] == 0xFF && header[1] == 0xD8;
            bool isPng = header[0] == 0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47;
            bool isGif = header[0] == (byte)'G' && header[1] == (byte)'I' && header[2] == (byte)'F' && header[3] == (byte)'8';
            bool isWebp = header[0] == (byte)'R' && header[1] == (byte)'I' && header[2] == (byte)'F' && header[3] == (byte)'F'
                          && header[8] == (byte)'W' && header[9] == (byte)'E' && header[10] == (byte)'B' && header[11] == (byte)'P';
            bool isAvif = header[4] == (byte)'f' && header[5] == (byte)'t' && header[6] == (byte)'y' && header[7] == (byte)'p';

            bool hasImageMagic = isJpeg || isPng || isGif || isWebp || isAvif;

            if (mime != null && !mime.StartsWith("image/", StringComparison.OrdinalIgnoreCase) && !hasImageMagic)
            {
                try { File.Delete(filePath); } catch { }
                throw new IOException($"Server returned non-image payload (MIME: {mime}). Possible HTML error page.");
            }
        }

        private static void ValidateChapter(string tempDir, int expectedCount)
        {
            var files = Directory.GetFiles(tempDir)
                .Where(f => !f.EndsWith(".part", StringComparison.OrdinalIgnoreCase))
                .Where(f => new FileInfo(f).Length >= 128)
                .ToList();

            if (files.Count != expectedCount)
            {
                throw new IOException($"Chapter validation failed: {files.Count}/{expectedCount} valid pages found");
            }
        }

        private static void FinishFolder(string mangaDir, string tempDir, string finalName)
        {
            var targetFolder = Path.Combine(mangaDir, finalName);
            if (Directory.Exists(targetFolder))
            {
                Directory.Delete(targetFolder, true);
            }
            Directory.Move(tempDir, targetFolder);
        }

        private static void FinishCbz(string mangaDir, string tempDir, string finalName, string jobId)
        {
            var targetCbz = Path.Combine(mangaDir, $"{finalName}.cbz");
            var pendingCbz = Path.Combine(mangaDir, $".{finalName}.cbz.{jobId[..Math.Min(8, jobId.Length)]}.downloading");

            if (File.Exists(pendingCbz)) File.Delete(pendingCbz);

            try
            {
                using (var zip = ZipFile.Open(pendingCbz, ZipArchiveMode.Create))
                {
                    var pages = Directory.GetFiles(tempDir)
                        .Where(f => !f.EndsWith(".part", StringComparison.OrdinalIgnoreCase))
                        .OrderBy(Path.GetFileName, StringComparer.OrdinalIgnoreCase);

                    foreach (var page in pages)
                    {
                        zip.CreateEntryFromFile(page, Path.GetFileName(page), CompressionLevel.Optimal);
                    }
                }

                if (File.Exists(targetCbz)) File.Delete(targetCbz);
                File.Move(pendingCbz, targetCbz);

                try { Directory.Delete(tempDir, true); } catch { }
            }
            catch
            {
                if (File.Exists(pendingCbz)) File.Delete(pendingCbz);
                throw;
            }
        }

        private static string GetImageExtension(string? mime, string url)
        {
            if (!string.IsNullOrEmpty(mime))
            {
                var cleanMime = mime.ToLowerInvariant().Split(';')[0].Trim();
                switch (cleanMime)
                {
                    case "image/jpeg": return ".jpg";
                    case "image/png": return ".png";
                    case "image/webp": return ".webp";
                    case "image/gif": return ".gif";
                    case "image/avif": return ".avif";
                    case "image/jxl": return ".jxl";
                }
            }

            try
            {
                var uri = new Uri(url);
                var ext = Path.GetExtension(uri.AbsolutePath);
                if (!string.IsNullOrEmpty(ext) && ext.Length is >= 2 and <= 5)
                {
                    return ext.ToLowerInvariant();
                }
            }
            catch { }

            return ".jpg";
        }

        public static string SafeName(string value)
        {
            if (string.IsNullOrWhiteSpace(value)) return "unnamed";
            var invalid = Path.GetInvalidFileNameChars();
            var sb = new StringBuilder(value.Length);
            foreach (var c in value)
            {
                if (invalid.Contains(c) || c is '/' or '\\' or ':' or '*' or '?' or '"' or '<' or '>' or '|')
                    sb.Append('_');
                else
                    sb.Append(c);
            }
            var result = sb.ToString().Trim().TrimEnd('.');
            if (result.Length > 160) result = result[..160].TrimEnd('.');
            return string.IsNullOrWhiteSpace(result) ? "unnamed" : result;
        }

        private void EnsureStorageThreshold()
        {
            var min = _config.GetMinimumFreeBytes();
            var free = _config.GetAvailableFreeBytes();
            if (free < 0 || free >= min) return;

            if (_config.AutoCleanup)
            {
                var completed = _jobs.Values
                    .Where(j => j.State.Status == "completed")
                    .OrderBy(j => j.State.UpdatedAt)
                    .ToList();

                foreach (var old in completed)
                {
                    if (free >= min) break;
                    if (DeleteCompletedOutput(old))
                    {
                        _jobs.TryRemove(old.State.JobId, out _);
                        free = _config.GetAvailableFreeBytes();
                    }
                }
                PersistJobs();
            }

            if (free is >= 0 && free < min)
            {
                throw new IOException($"Minimum free disk space reached ({FormatBytes(free)} available, {FormatBytes(min)} required)");
            }
        }

        private bool DeleteCompletedOutput(JobRecord record)
        {
            try
            {
                var downloadsDir = _config.GetDownloadsDir();
                var sourceDir = Path.Combine(downloadsDir, SafeName(record.Request.SourceDir));
                var mangaDir = Path.Combine(sourceDir, SafeName(record.Request.MangaDir));
                var finalName = SafeName(record.Request.ChapterDir);

                var cbz = Path.Combine(mangaDir, $"{finalName}.cbz");
                if (File.Exists(cbz)) { File.Delete(cbz); return true; }

                var folder = Path.Combine(mangaDir, finalName);
                if (Directory.Exists(folder)) { Directory.Delete(folder, true); return true; }
            }
            catch { }

            return false;
        }

        private void PersistJobs()
        {
            try
            {
                var list = _jobs.Values.Select(j => new { body = j.Request, state = j.State }).ToList();
                var json = JsonSerializer.Serialize(list, JsonOpts);
                File.WriteAllText(_jobsFilePath, json);
            }
            catch { }
        }

        private void RestoreJobs()
        {
            if (!File.Exists(_jobsFilePath)) return;
            try
            {
                using var doc = JsonDocument.Parse(File.ReadAllText(_jobsFilePath));
                foreach (var elem in doc.RootElement.EnumerateArray())
                {
                    var bodyJson = elem.GetProperty("body").GetRawText();
                    var stateJson = elem.GetProperty("state").GetRawText();
                    var req = JsonSerializer.Deserialize<JobCreateRequest>(bodyJson);
                    var state = JsonSerializer.Deserialize<JobState>(stateJson);

                    if (req != null && state != null && !string.IsNullOrEmpty(state.JobId))
                    {
                        _jobs[state.JobId] = new JobRecord(req, state);
                    }
                }
            }
            catch { }
        }

        private void CleanupHistory()
        {
            long cutoff = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - (_config.HistoryDays * 86_400_000L);
            foreach (var kvp in _jobs)
            {
                if (TerminalStates.Contains(kvp.Value.State.Status) && kvp.Value.State.UpdatedAt < cutoff)
                {
                    _jobs.TryRemove(kvp.Key, out _);
                }
            }
            PersistJobs();
        }

        private void ResumeQueuedJobs()
        {
            foreach (var job in _jobs.Values)
            {
                if (job.State.Status is "queued" or "downloading")
                {
                    job.State.Status = "queued";
                    _ = Task.Run(() => ExecuteJobAsync(job));
                }
            }
        }

        public static string FormatBytes(long bytes)
        {
            if (bytes < 0) return "Unknown";
            if (bytes >= 1L << 30) return $"{(bytes / (double)(1L << 30)).ToString("F1", System.Globalization.CultureInfo.InvariantCulture)} GB";
            if (bytes >= 1L << 20) return $"{(bytes / (double)(1L << 20)).ToString("F1", System.Globalization.CultureInfo.InvariantCulture)} MB";
            if (bytes >= 1L << 10) return $"{(bytes / (double)(1L << 10)).ToString("F1", System.Globalization.CultureInfo.InvariantCulture)} KB";
            return $"{bytes} B";
        }

        private static void LogMessage(ConsoleColor color, string message)
        {
            Console.ForegroundColor = color;
            Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] {message}");
            Console.ResetColor();
        }
    }
}
