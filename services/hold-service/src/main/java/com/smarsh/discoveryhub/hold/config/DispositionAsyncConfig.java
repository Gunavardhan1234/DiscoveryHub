package com.smarsh.discoveryhub.hold.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Dedicated single-thread executor for disposition runs (FR-5). The
 * manual trigger and the scheduled tick both hand the actual scan-and-delete
 * pass to this thread so the HTTP request returns immediately with a RUNNING
 * ledger row instead of blocking for as long as the pass takes over a large
 * corpus — the Disposition screen polls for completion instead.
 */
@Configuration
public class DispositionAsyncConfig {

    @Bean(name = "dispositionExecutor")
    public Executor dispositionExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "disposition-runner");
            t.setDaemon(true);
            return t;
        });
    }
}
