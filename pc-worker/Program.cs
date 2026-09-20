using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace MihonPcWorker
{
    public class Program
    {
        public static async Task Main(string[] args)
        {
            Console.OutputEncoding = Encoding.UTF8;
            var baseDir = AppContext.BaseDirectory;
            var configPath = Path.Combine(baseDir, "config.json");
            var config = WorkerConfig.LoadOrCreate(configPath);

            // Command-Line Arguments Parsing
            for (int i = 0; i < args.Length; i++)
            {
                var arg = args[i];
                if ((arg.Equals("--storage", StringComparison.OrdinalIgnoreCase) || arg.Equals("-s", StringComparison.OrdinalIgnoreCase)) && i + 1 < args.Length)
                {
                    config.StorageRoot = args[++i];
                    config.Save(configPath);
                }
                else if ((arg.Equals("--port", StringComparison.OrdinalIgnoreCase) || arg.Equals("-p", StringComparison.OrdinalIgnoreCase)) && i + 1 < args.Length)
                {
                    if (int.TryParse(args[++i], out var p) && p is > 0 and < 65536)
                    {
                        config.Port = p;
                        config.Save(configPath);
                    }
                }
                else if ((arg.Equals("--lang", StringComparison.OrdinalIgnoreCase) || arg.Equals("-l", StringComparison.OrdinalIgnoreCase)) && i + 1 < args.Length)
                {
                    var lang = args[++i].ToLowerInvariant();
                    if (lang is "en" or "id")
                    {
                        config.Language = lang;
                        config.Save(configPath);
                    }
                }
            }

            var engine = new DownloadEngine(config, baseDir);

            var builder = WebApplication.CreateBuilder(args);
            builder.Logging.ClearProviders();

            builder.WebHost.ConfigureKestrel(opts =>
            {
                opts.ListenAnyIP(config.Port);
            });

            var app = builder.Build();

            // 1. Status Endpoints
            app.MapGet("/api/v2/status", () => GetStatus(config, engine));
            app.MapGet("/api/v1/status", () => GetStatus(config, engine));

            // 2. Jobs Listing
            app.MapGet("/api/v2/jobs", () =>
            {
                var list = engine.GetAllJobs()
                    .OrderByDescending(j => j.State.UpdatedAt)
                    .Select(j => j.State)
                    .ToList();
                return Results.Json(new JobsListResponse { Jobs = list });
            });

            // 3. Job Details
            app.MapGet("/api/v2/jobs/{id}", (string id) =>
            {
                var record = engine.GetJob(id);
                return record != null
                    ? Results.Json(record.State)
                    : Results.Json(new ErrorResponse("job not found"), statusCode: StatusCodes.Status404NotFound);
            });
            app.MapGet("/api/v1/jobs/{id}", (string id) =>
            {
                var record = engine.GetJob(id);
                return record != null
                    ? Results.Json(record.State)
                    : Results.Json(new ErrorResponse("job not found"), statusCode: StatusCodes.Status404NotFound);
            });

            // 4. Create Job
            app.MapPost("/api/v2/jobs", (JobCreateRequest req) => CreateJob(req, engine));
            app.MapPost("/api/v1/jobs", (JobCreateRequest req) => CreateJob(req, engine));

            // 5. Refresh Job URLs
            app.MapPost("/api/v2/jobs/{id}/refresh", (string id, RefreshJobRequest req) =>
            {
                if (!engine.RefreshJob(id, req.Pages))
                {
                    return Results.Json(new ErrorResponse("job not found"), statusCode: StatusCodes.Status404NotFound);
                }
                return Results.Json(engine.GetJob(id)!.State, statusCode: StatusCodes.Status202Accepted);
            });

            // 6. Control Single Job
            app.MapPost("/api/v2/jobs/{id}/{action}", (string id, string action) =>
            {
                if (!engine.ControlJob(id, action))
                {
                    return Results.Json(new ErrorResponse("job not found"), statusCode: StatusCodes.Status404NotFound);
                }
                return Results.Json(engine.GetJob(id)!.State);
            });

            // 7. Control All Jobs
            app.MapPost("/api/v2/jobs/{action}", (string action) =>
            {
                int affected = engine.ControlAll(action);
                return Results.Json(new MessageResponse { Message = $"{action} applied", Jobs = affected });
            });

            // 8. Storage Configuration (Set from Mihon or API)
            app.MapPost("/api/v2/config/storage", (SetStorageRequest req) =>
            {
                var newPath = req.StorageRoot?.Trim() ?? "";
                if (string.IsNullOrWhiteSpace(newPath) || newPath == "/" || newPath == "\\" || newPath.StartsWith("Belum diatur", StringComparison.OrdinalIgnoreCase))
                {
                    return Results.Json(new { success = true, storageRoot = config.StorageRoot, message = "Ignored placeholder storage path" });
                }

                try
                {
                    if (!Directory.Exists(newPath))
                    {
                        Directory.CreateDirectory(newPath);
                    }
                    config.StorageRoot = newPath;
                    config.Save(configPath);
                    PrintBanner(config, engine);
                    return Results.Json(new { success = true, storageRoot = config.StorageRoot, message = "Storage path updated successfully" });
                }
                catch (Exception ex)
                {
                    return Results.Json(new ErrorResponse($"Cannot set storage path: {ex.Message}"), statusCode: StatusCodes.Status400BadRequest);
                }
            });

            // 9. Get Config
            app.MapGet("/api/v2/config", () =>
            {
                return Results.Json(new
                {
                    storageRoot = config.StorageRoot,
                    port = config.Port,
                    language = config.Language,
                    defaultCbz = config.DefaultCbz,
                    concurrency = config.Concurrency
                });
            });

            // Start Kestrel Server
            _ = app.RunAsync();

            // Print Console UI
            PrintBanner(config, engine);

            // Background Keyboard Listener
            while (true)
            {
                if (!Console.IsInputRedirected)
                {
                    try
                    {
                        if (Console.KeyAvailable)
                        {
                            var key = Console.ReadKey(intercept: true).Key;
                            if (key == ConsoleKey.Q)
                            {
                                Console.WriteLine("\n[Shutdown] Stopping Mihon Download Worker...");
                                await app.StopAsync();
                                break;
                            }
                            else if (key == ConsoleKey.L)
                            {
                                config.Language = config.Language.Equals("en", StringComparison.OrdinalIgnoreCase) ? "id" : "en";
                                config.Save(configPath);
                                PrintBanner(config, engine);
                            }
                            else if (key == ConsoleKey.C)
                            {
                                var dir = config.GetDownloadsDir();
                                try
                                {
                                    Process.Start(new ProcessStartInfo
                                    {
                                        FileName = "explorer.exe",
                                        Arguments = $"\"{dir}\"",
                                        UseShellExecute = true
                                    });
                                    Console.WriteLine($"\n[Explorer] Opening {dir}...");
                                }
                                catch (Exception ex)
                                {
                                    Console.WriteLine($"\n[Explorer] Failed to open: {ex.Message}");
                                }
                            }
                            else if (key == ConsoleKey.D)
                            {
                                var isEn = config.Language.Equals("en", StringComparison.OrdinalIgnoreCase);
                                Console.ForegroundColor = ConsoleColor.Cyan;
                                Console.Write(isEn
                                    ? $"\n[Storage Path] Current: {config.StorageRoot}\n Enter new path (e.g. D:\\Manga, or Enter to cancel): "
                                    : $"\n[Lokasi Simpan] Saat ini: {config.StorageRoot}\n Masukkan path baru (contoh D:\\Manga, atau tekan Enter untuk batal): ");
                                Console.ResetColor();
                                var newPath = Console.ReadLine()?.Trim();
                                if (!string.IsNullOrEmpty(newPath))
                                {
                                    try
                                    {
                                        if (!Directory.Exists(newPath)) Directory.CreateDirectory(newPath);
                                        config.StorageRoot = newPath;
                                        config.Save(configPath);
                                        Console.ForegroundColor = ConsoleColor.Green;
                                        Console.WriteLine(isEn ? $"[Storage Path] Successfully changed to: {config.StorageRoot}" : $"[Lokasi Simpan] Berhasil diubah ke: {config.StorageRoot}");
                                        Console.ResetColor();
                                        await Task.Delay(1200);
                                        PrintBanner(config, engine);
                                    }
                                    catch (Exception ex)
                                    {
                                        Console.ForegroundColor = ConsoleColor.Red;
                                        Console.WriteLine(isEn ? $"[Error] Cannot use directory: {ex.Message}" : $"[Error] Folder tidak dapat digunakan: {ex.Message}");
                                        Console.ResetColor();
                                        await Task.Delay(2000);
                                        PrintBanner(config, engine);
                                    }
                                }
                                else
                                {
                                    PrintBanner(config, engine);
                                }
                            }
                            else if (key == ConsoleKey.S)
                            {
                                PrintBanner(config, engine);
                            }
                        }
                    }
                    catch { }
                }
                await Task.Delay(300);
            }
        }

        private static IResult GetStatus(WorkerConfig config, DownloadEngine engine)
        {
            var downloadsDir = config.GetDownloadsDir();
            bool hasStorage = !string.IsNullOrWhiteSpace(downloadsDir) && Directory.Exists(downloadsDir);
            var free = config.GetAvailableFreeBytes();
            var status = new StatusResponse
            {
                Message = $"Download Worker connected; storage={(hasStorage ? "ready" : "not configured")}",
                Version = 2,
                ActiveJobs = engine.GetActiveJobsCount(),
                TotalJobs = engine.GetTotalJobsCount(),
                FreeBytes = free,
                MinimumFreeBytes = config.GetMinimumFreeBytes(),
                StorageRoot = config.StorageRoot
            };
            return Results.Json(status);
        }

        private static IResult CreateJob(JobCreateRequest req, DownloadEngine engine)
        {
            try
            {
                var record = engine.Enqueue(req);
                return Results.Json(new { jobId = record.State.JobId, status = "queued" }, statusCode: StatusCodes.Status202Accepted);
            }
            catch (InvalidOperationException)
            {
                return Results.Json(new ErrorResponse("job already exists"), statusCode: StatusCodes.Status409Conflict);
            }
            catch (Exception ex)
            {
                return Results.Json(new ErrorResponse(ex.Message), statusCode: StatusCodes.Status500InternalServerError);
            }
        }

        private static void PrintBanner(WorkerConfig config, DownloadEngine engine)
        {
            bool isEn = config.Language.Equals("en", StringComparison.OrdinalIgnoreCase);

            try
            {
                if (!Console.IsOutputRedirected) Console.Clear();
            }
            catch { }

            Console.ForegroundColor = ConsoleColor.Cyan;
            Console.WriteLine("========================================================================");
            Console.WriteLine(isEn
                ? "         MIHON DOWNLOAD WORKER (PC ENGINE) - v2.0                       "
                : "         MIHON WORKER (MESIN PC) - v2.0                                ");
            Console.WriteLine("========================================================================");
            Console.ResetColor();

            var storagePathDisplay = !string.IsNullOrWhiteSpace(config.StorageRoot)
                ? config.StorageRoot
                : (isEn ? "[Not configured - Waiting for Mihon setup]" : "[Belum diatur - Menunggu disetel dari Mihon]");

            Console.WriteLine($" [Status]          : ONLINE (Port {config.Port})");
            Console.WriteLine(isEn ? $" [Storage Path]    : {storagePathDisplay}" : $" [Lokasi Simpan]   : {storagePathDisplay}");

            var free = config.GetAvailableFreeBytes();
            var freeStr = free >= 0 ? DownloadEngine.FormatBytes(free) + (isEn ? " Free" : " Tersedia") : (isEn ? "Unknown" : "Tidak diketahui");
            Console.WriteLine(isEn ? $" [Free Disk Space] : {freeStr}" : $" [Sisa Ruang Disk] : {freeStr}");

            Console.ForegroundColor = ConsoleColor.Green;
            Console.WriteLine(isEn ? "\n [PC IP Addresses]:" : "\n [Alamat IP PC]:");
            var ips = GetLocalIpAddresses();
            foreach (var (name, ip) in ips)
            {
                Console.WriteLine($"   - {name,-25} : http://{ip}:{config.Port}");
            }
            Console.ResetColor();

            Console.ForegroundColor = ConsoleColor.DarkGray;
            if (isEn)
            {
                Console.WriteLine("\n Keyboard Shortcuts:");
                Console.WriteLine("   [D] Change Storage Path         [C] Open Downloads Folder");
                Console.WriteLine("   [L] Switch Language (EN/ID)     [Q] Stop Worker");
            }
            else
            {
                Console.WriteLine("\n Navigasi Keyboard:");
                Console.WriteLine("   [D] Ubah Lokasi Simpan          [C] Buka Folder Downloads");
                Console.WriteLine("   [L] Ganti Bahasa (EN/ID)        [Q] Tutup Worker");
            }
            Console.WriteLine("========================================================================\n");
            Console.ResetColor();
        }

        private static (string name, string ip)[] GetLocalIpAddresses()
        {
            var list = new System.Collections.Generic.List<(string name, string ip)>();
            try
            {
                foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != OperationalStatus.Up) continue;
                    if (ni.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;

                    var ipProps = ni.GetIPProperties();
                    foreach (var addr in ipProps.UnicastAddresses)
                    {
                        if (addr.Address.AddressFamily == AddressFamily.InterNetwork)
                        {
                            var ipStr = addr.Address.ToString();
                            list.Add((ni.Name, ipStr));
                        }
                    }
                }
            }
            catch { }

            // Add localhost
            list.Add(("Localhost", "127.0.0.1"));
            return list.ToArray();
        }
    }
}
