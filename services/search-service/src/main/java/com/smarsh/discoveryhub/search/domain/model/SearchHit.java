package com.smarsh.discoveryhub.search.domain.model;

/**
 * A single result row returned by {@code POST /api/search}.
 *
 * <p><b>OOP — immutable value object:</b> a {@code record}; no behaviour, no
 * setters, safe to share across threads.
 *
 * <p><b>SOLID — ISP applied to data (NFR-3, "search under 2 s"):</b> this is
 * deliberately a <em>narrow</em> view of a message — id, subject, a highlighted
 * {@code snippet}, score and the few filterable fields the results table
 * displays. It does not carry the full body, participants or attachment
 * metadata, so the list view never pays to transfer content it will not show;
 * the full record is fetched from archive-service only when a user opens a
 * message. Clients depend only on the fields they actually use.
 */
public record SearchHit(
        String messageId,
        String subject,
        String snippet,
        float score,
        String custodianId,
        String timestamp,
        String type,
        boolean hasAttachment,
        boolean onHold) {}
