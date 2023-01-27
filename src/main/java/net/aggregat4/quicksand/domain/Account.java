package net.aggregat4.quicksand.domain;

public record Account(int id, String name, String imapHost, String imapUsername, String imapPassword, String smtpHost, String smtpUsername, String smtpPassword) { }
