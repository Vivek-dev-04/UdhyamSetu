package com.shopbilling.service;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Pluggable client contract for uploading encrypted backups to remote/cloud storage (PRD Section 7.11 FR-47).
 * Pure interface: zero UI or platform-specific cloud lock-in.
 */
public interface CloudStorageClient {

    /**
     * Checks if cloud storage connectivity is currently operational.
     */
    boolean isAvailable();

    /**
     * Uploads the local encrypted file to remote destination under remoteFileName.
     * @return true if successfully uploaded, false if rejected or failed gracefully.
     */
    boolean upload(String remoteFileName, Path localFile) throws IOException;

    /**
     * Downloads a remote backup file into local destination path.
     * @return true if successfully downloaded.
     */
    boolean download(String remoteFileName, Path localDestination) throws IOException;
}
