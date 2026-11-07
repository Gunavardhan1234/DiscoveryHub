package com.smarsh.discoveryhub.events;

public record ExportJob(String jobId, String caseId, String holdId) {

    public static ExportJob queued(String jobId, String caseId, String holdId) {
        return new ExportJob(jobId, caseId, holdId);
    }
}
