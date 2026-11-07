package com.smarsh.discoveryhub.generator.model;

import java.util.List;

/**
 * The custodian roster (FR-1.2: &ge;20 custodians). A fixed, deterministic
 * list so a re-run reproduces the same {@code externalMessageId}s.
 */
public final class CustodianRoster {

    public static final List<Custodian> CUSTODIANS = List.of(
            new Custodian("c-0001", "Ada Byron", "ada@fict.example"),
            new Custodian("c-0002", "Charlie Babbage", "charlie@fict.example"),
            new Custodian("c-0003", "Grace Hopper", "grace@fict.example"),
            new Custodian("c-0004", "Alan Turing", "alan@fict.example"),
            new Custodian("c-0005", "Margaret Hamilton", "margaret@fict.example"),
            new Custodian("c-0006", "Linus Torvalds", "linus@fict.example"),
            new Custodian("c-0007", "Katherine Johnson", "katherine@fict.example"),
            new Custodian("c-0008", "Dennis Ritchie", "dennis@fict.example"),
            new Custodian("c-0009", "Barbara Liskov", "barbara@fict.example"),
            new Custodian("c-0010", "Tim Berners-Lee", "tim@fict.example"),
            new Custodian("c-0011", "Radia Perlman", "radia@fict.example"),
            new Custodian("c-0012", "Donald Knuth", "donald@fict.example"),
            new Custodian("c-0013", "Edsger Dijkstra", "edsger@fict.example"),
            new Custodian("c-0014", "Anita Borg", "anita@fict.example"),
            new Custodian("c-0015", "John Carmack", "john@fict.example"),
            new Custodian("c-0016", "Ken Thompson", "ken@fict.example"),
            new Custodian("c-0017", "Niklaus Wirth", "niklaus@fict.example"),
            new Custodian("c-0018", "Claude Shannon", "claude@fict.example"),
            new Custodian("c-0019", "Hedy Lamarr", "hedy@fict.example"),
            new Custodian("c-0020", "Brian Kernighan", "brian@fict.example"),
            new Custodian("c-0021", "Edith Clarke", "edith@fict.example"),
            new Custodian("c-0022", "Sophie Wilson", "sophie@fict.example"),
            new Custodian("c-0023", "Vinton Cerf", "vint@fict.example"),
            new Custodian("c-0024", "Mary Jackson", "mary@fict.example"),
            new Custodian("c-0025", "Anders Hejlsberg", "anders@fict.example"));

    private CustodianRoster() {}
}
