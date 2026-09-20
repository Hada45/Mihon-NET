using System;
using System.Collections.Generic;
using System.Text.Json.Serialization;
using System.Threading;

namespace MihonPcWorker
{
    public class PairRequest
    {
        [JsonPropertyName("code")]
        public string? Code { get; set; }
    }

    public class PairResponse
    {
        [JsonPropertyName("token")]
        public string Token { get; set; } = "";

        [JsonPropertyName("message")]
        public string Message { get; set; } = "paired";
    }

    public class StatusResponse
    {
        [JsonPropertyName("message")]
        public string Message { get; set; } = "";

        [JsonPropertyName("version")]
        public int Version { get; set; } = 2;

        [JsonPropertyName("activeJobs")]
        public int ActiveJobs { get; set; }

        [JsonPropertyName("totalJobs")]
        public int TotalJobs { get; set; }

        [JsonPropertyName("freeBytes")]
        public long FreeBytes { get; set; }

        [JsonPropertyName("minimumFreeBytes")]
        public long MinimumFreeBytes { get; set; }

        [JsonPropertyName("storageRoot")]
        public string? StorageRoot { get; set; }
    }

    public class SetStorageRequest
    {
        [JsonPropertyName("storageRoot")]
        public string StorageRoot { get; set; } = "";
    }

    public class PageItem
    {
        [JsonPropertyName("index")]
        public int Index { get; set; }

        [JsonPropertyName("url")]
        public string Url { get; set; } = "";

        [JsonPropertyName("headers")]
        public Dictionary<string, string>? Headers { get; set; }
    }

    public class JobCreateRequest
    {
        [JsonPropertyName("jobId")]
        public string? JobId { get; set; }

        [JsonPropertyName("mangaTitle")]
        public string MangaTitle { get; set; } = "";

        [JsonPropertyName("chapterTitle")]
        public string ChapterTitle { get; set; } = "";

        [JsonPropertyName("sourceDir")]
        public string SourceDir { get; set; } = "";

        [JsonPropertyName("mangaDir")]
        public string MangaDir { get; set; } = "";

        [JsonPropertyName("chapterDir")]
        public string ChapterDir { get; set; } = "";

        [JsonPropertyName("outputMode")]
        public string? OutputMode { get; set; }

        [JsonPropertyName("pages")]
        public List<PageItem> Pages { get; set; } = new();
    }

    public class RefreshJobRequest
    {
        [JsonPropertyName("pages")]
        public List<PageItem> Pages { get; set; } = new();
    }

    public class JobState
    {
        [JsonPropertyName("jobId")]
        public string JobId { get; set; } = "";

        [JsonPropertyName("title")]
        public string Title { get; set; } = "";

        [JsonPropertyName("status")]
        public string Status { get; set; } = "queued";

        [JsonPropertyName("completed")]
        public int Completed { get; set; }

        [JsonPropertyName("total")]
        public int Total { get; set; }

        [JsonPropertyName("error")]
        [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
        public string? Error { get; set; }

        [JsonPropertyName("bytesDownloaded")]
        public long BytesDownloaded { get; set; }

        [JsonPropertyName("startedAt")]
        public long StartedAt { get; set; }

        [JsonPropertyName("updatedAt")]
        public long UpdatedAt { get; set; } = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

        [JsonIgnore]
        public bool Paused { get; set; }

        [JsonIgnore]
        public bool Cancelled { get; set; }
    }

    public class JobRecord
    {
        public JobCreateRequest Request { get; set; }
        public JobState State { get; set; }

        [JsonIgnore]
        public CancellationTokenSource? Cts { get; set; }

        public JobRecord(JobCreateRequest request, JobState state)
        {
            Request = request;
            State = state;
        }
    }

    public class JobsListResponse
    {
        [JsonPropertyName("jobs")]
        public List<JobState> Jobs { get; set; } = new();
    }

    public class MessageResponse
    {
        [JsonPropertyName("message")]
        public string Message { get; set; } = "";

        [JsonPropertyName("jobs")]
        [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingDefault)]
        public int Jobs { get; set; }
    }

    public class ErrorResponse
    {
        [JsonPropertyName("error")]
        public string Error { get; set; } = "";

        public ErrorResponse(string error) => Error = error;
    }
}
