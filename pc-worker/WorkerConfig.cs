using System;
using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace MihonPcWorker
{
    public class WorkerConfig
    {
        public int Port { get; set; } = 2223;
        public string StorageRoot { get; set; } = "";
        public string Language { get; set; } = "id";
        public int Concurrency { get; set; } = 3;
        public bool DefaultCbz { get; set; } = true;
        public int MinFreeGb { get; set; } = 2;
        public bool AutoCleanup { get; set; } = false;
        public int HistoryDays { get; set; } = 7;

        private static readonly JsonSerializerOptions JsonOpts = new()
        {
            WriteIndented = true,
            DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
        };

        public static WorkerConfig LoadOrCreate(string configPath)
        {
            WorkerConfig? config = null;
            if (File.Exists(configPath))
            {
                try
                {
                    var text = File.ReadAllText(configPath);
                    config = JsonSerializer.Deserialize<WorkerConfig>(text, JsonOpts);
                }
                catch (Exception ex)
                {
                    Console.ForegroundColor = ConsoleColor.Yellow;
                    Console.WriteLine($"[Config] Warning: Failed to read {configPath} ({ex.Message}), creating new.");
                    Console.ResetColor();
                }
            }

            if (config == null)
            {
                config = new WorkerConfig();
            }

            if (!File.Exists(configPath))
            {
                config.Save(configPath);
            }

            return config;
        }

        public void Save(string configPath)
        {
            try
            {
                var dir = Path.GetDirectoryName(configPath);
                if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir))
                {
                    Directory.CreateDirectory(dir);
                }
                var json = JsonSerializer.Serialize(this, JsonOpts);
                File.WriteAllText(configPath, json);
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[Config] Error saving config: {ex.Message}");
            }
        }

        public string GetDownloadsDir()
        {
            var root = StorageRoot.Trim();
            if (string.IsNullOrEmpty(root))
            {
                return "";
            }
            if (!Directory.Exists(root))
            {
                try
                {
                    Directory.CreateDirectory(root);
                }
                catch
                {
                    return "";
                }
            }
            return root;
        }

        public long GetAvailableFreeBytes()
        {
            try
            {
                var dir = GetDownloadsDir();
                if (string.IsNullOrEmpty(dir))
                {
                    return -1;
                }
                var root = Path.GetPathRoot(dir);
                if (!string.IsNullOrEmpty(root))
                {
                    var drive = new DriveInfo(root);
                    if (drive.IsReady)
                    {
                        return drive.AvailableFreeSpace;
                    }
                }
            }
            catch { }

            return -1;
        }

        public long GetMinimumFreeBytes()
        {
            return (long)MinFreeGb * 1024 * 1024 * 1024;
        }
    }
}
