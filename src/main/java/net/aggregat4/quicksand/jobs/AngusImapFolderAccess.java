package net.aggregat4.quicksand.jobs;

import jakarta.mail.FetchProfile;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.UIDFolder;
import jakarta.mail.event.MailEvent;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.MessageVanishedEvent;
import org.eclipse.angus.mail.imap.ResyncData;

final class AngusImapFolderAccess implements ImapFolderAccess {

  private final IMAPFolder folder;
  private long[] vanishedUids = new long[0];
  private boolean openedWithQresync;

  AngusImapFolderAccess(IMAPFolder folder) {
    this.folder = folder;
  }

  @Override
  public void openReadOnly(ImapSyncOpenParameters parameters) throws MessagingException {
    vanishedUids = new long[0];
    openedWithQresync = false;

    ResyncData resyncData = resolveResyncData(parameters);
    if (resyncData != null) {
      openedWithQresync = resyncData != ResyncData.CONDSTORE;
      List<MailEvent> events = folder.open(jakarta.mail.Folder.READ_ONLY, resyncData);
      if (events != null) {
        vanishedUids = collectVanishedUids(events);
      }
      return;
    }
    folder.open(jakarta.mail.Folder.READ_ONLY);
  }

  @Override
  public boolean openedWithQresync() {
    return openedWithQresync;
  }

  @Override
  public long[] getVanishedUids() {
    return vanishedUids;
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

  private static ResyncData resolveResyncData(ImapSyncOpenParameters parameters) {
    if (parameters.qresyncSupported()
        && parameters.uidValidity() != null
        && parameters.highestModSeq() != null
        && parameters.highestModSeq() > 0) {
      return new ResyncData(parameters.uidValidity(), parameters.highestModSeq());
    }
    if (parameters.condstoreSupported()) {
      return ResyncData.CONDSTORE;
    }
    return null;
  }

  private static long[] collectVanishedUids(List<MailEvent> events) {
    List<Long> uids = new ArrayList<>();
    for (MailEvent event : events) {
      if (event instanceof MessageVanishedEvent vanished) {
        for (long uid : vanished.getUIDs()) {
          uids.add(uid);
        }
      }
    }
    return uids.stream().mapToLong(Long::longValue).toArray();
  }
}
