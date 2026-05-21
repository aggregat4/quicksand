package net.aggregat4.quicksand.jobs;

import jakarta.mail.FetchProfile;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import org.eclipse.angus.mail.imap.IMAPFolder;

interface ImapFolderAccess extends AutoCloseable {

  void openReadOnly(ImapSyncOpenParameters parameters) throws MessagingException;

  boolean openedWithQresync();

  long[] getVanishedUids();

  boolean isOpen();

  long getUidValidity() throws MessagingException;

  long getHighestModSeq() throws MessagingException;

  Message[] getMessagesByUIDChangedSince(long modseq) throws MessagingException;

  Message[] getMessages() throws MessagingException;

  void fetch(Message[] messages, FetchProfile profile) throws MessagingException;

  long getUid(Message message) throws MessagingException;

  String getFullName() throws MessagingException;

  IMAPFolder unwrapImapFolder();

  @Override
  void close() throws MessagingException;
}
