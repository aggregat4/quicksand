package net.aggregat4.quicksand.domain;

public record Account(int id, String name, String imapHost, int imapPort, String imapUsername, String imapPassword, String smtpHost, int smtpPort, String smtpUsername, String smtpPassword) { }
