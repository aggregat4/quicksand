package net.aggregat4.quicksand.jobs;

import jakarta.mail.FetchProfile;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.UIDFolder;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.ResyncData;

interface ImapFolderAccess extends AutoCloseable {

  void openReadOnly(boolean enableCondstore) throws MessagingException;

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

final class AngusImapFolderAccess implements ImapFolderAccess {

  private final IMAPFolder folder;

  AngusImapFolderAccess(IMAPFolder folder) {
    this.folder = folder;
  }

  @Override
  public void openReadOnly(boolean enableCondstore) throws MessagingException {
    if (enableCondstore) {
      folder.open(jakarta.mail.Folder.READ_ONLY, ResyncData.CONDSTORE);
    } else {
      folder.open(jakarta.mail.Folder.READ_ONLY);
    }
  }

  @Override
  public boolean isOpen() {
    return folder.isOpen();
  }

  @Override
  public long getUidValidity() throws MessagingException {
    return folder.getUIDValidity();
  }

  @Override
  public long getHighestModSeq() throws MessagingException {
    return folder.getHighestModSeq();
  }

  @Override
  public Message[] getMessagesByUIDChangedSince(long modseq) throws MessagingException {
    return folder.getMessagesByUIDChangedSince(1, UIDFolder.LASTUID, modseq);
  }

  @Override
  public Message[] getMessages() throws MessagingException {
    return folder.getMessages();
  }

  @Override
  public void fetch(Message[] messages, FetchProfile profile) throws MessagingException {
    folder.fetch(messages, profile);
  }

  @Override
  public long getUid(Message message) throws MessagingException {
    return folder.getUID((org.eclipse.angus.mail.imap.IMAPMessage) message);
  }

  @Override
  public String getFullName() throws MessagingException {
    return folder.getFullName();
  }

  @Override
  public IMAPFolder unwrapImapFolder() {
    return folder;
  }

  @Override
  public void close() throws MessagingException {
    if (folder.isOpen()) {
      folder.close(false);
    }
  }
}
