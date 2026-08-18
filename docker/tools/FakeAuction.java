import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;

import org.jivesoftware.smack.Chat;
import org.jivesoftware.smack.ChatManagerListener;
import org.jivesoftware.smack.MessageListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.packet.Message;

/**
 * Interactive stand-in for a real auction house, so you can drive the
 * Auction Sniper app's UI by hand. Logs in as auction-<itemId>@localhost
 * (same account/protocol the tests' FakeAuctionServer uses -- see
 * test/end-to-end/test/endtoend/auctionsniper/FakeAuctionServer.java) and
 * lets you type SOL message bodies that get sent as chat messages to
 * whichever sniper joined.
 *
 * Ported from goos-ts's poc/tools/fake-auction.ts (see its
 * poc/docs/fake-auction.md), minus the --remote scenario -- this tool
 * only ever talks to the local Openfire server.
 *
 * Usage (inside the toolbox container, on the same classpath as the
 * app + lib/deploy jars):
 *   java FakeAuction <itemId>
 *
 * Once a sniper has joined, type a SOL message body (without the
 * "SOLVersion: 1.1; " prefix, which gets added automatically) to send
 * it, e.g.:
 *   Event: PRICE; CurrentPrice: 90; Increment: 5; Bidder: other bidder;
 *   Event: CLOSE;
 * Type "quit" to disconnect and exit.
 *
 * To simulate the sniper's own bid landing, the Bidder field must be the
 * sniper's full JID (e.g. "sniper@localhost/Auction"), not just its
 * username -- see AuctionMessageTranslator.isFrom().
 */
public class FakeAuction {
  private static final String SOL_VERSION_PREFIX = "SOLVersion: 1.1; ";
  private static final String AUCTION_RESOURCE = "Auction";
  private static final String AUCTION_PASSWORD = "auction";
  private static final String XMPP_HOSTNAME = "localhost";
  private static final String PROMPT = ">>> ";

  // Smack delivers incoming messages on its own reader thread, which races
  // against the main thread printing the ">>> " prompt while blocked on
  // Scanner input. Serialize output through this lock, and have async
  // messages clear/redraw the prompt line (like goos-ts's printAsync()
  // does with Node's readline.clearLine/cursorTo) so the two don't
  // interleave mid-line -- see printStandaloneAsync() and
  // printAttachedAsync() below.
  private static final Object CONSOLE_LOCK = new Object();

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("usage: java FakeAuction <itemId>");
      System.exit(1);
    }
    String itemId = args[0];
    String username = "auction-" + itemId;
    String jid = username + "@" + XMPP_HOSTNAME + "/" + AUCTION_RESOURCE;

    XMPPConnection connection = new XMPPConnection(XMPP_HOSTNAME);
    connection.connect();
    connection.login(username, AUCTION_PASSWORD, AUCTION_RESOURCE);

    final Chat[] sniperChat = new Chat[1];
    connection.getChatManager().addChatListener(new ChatManagerListener() {
      public void chatCreated(Chat chat, boolean createdLocally) {
        sniperChat[0] = chat;
        chat.addMessageListener(new MessageListener() {
          public void processMessage(Chat chat, Message message) {
            Map<String, String> fields = parseCommand(message.getBody());
            String command = fields.get("Command");
            if ("JOIN".equals(command)) {
              printStandaloneAsync("> Sniper joined: " + chat.getParticipant());
            } else if ("BID".equals(command)) {
              printAttachedAsync(
                  "< received: Bid " + fields.get("Price") + " from " + chat.getParticipant());
            }
          }
        });
      }
    });

    System.out.println(
        "Selling item " + itemId + " as " + jid + ". Waiting for a sniper to join...");
    System.out.println(
        "Type a SOL message body (without the \"" + SOL_VERSION_PREFIX + "\" prefix) to send it, e.g.:");
    System.out.println("  Event: PRICE; CurrentPrice: 90; Increment: 5; Bidder: other bidder;");
    System.out.println("  Event: CLOSE;");
    System.out.println("Type \"quit\" to disconnect and exit.");

    Scanner scanner = new Scanner(System.in);
    while (true) {
      printPrompt();
      if (!scanner.hasNextLine()) break;
      String line = scanner.nextLine().trim();
      if (line.isEmpty()) continue;
      if (line.equals("quit")) break;

      if (sniperChat[0] == null) {
        synchronized (CONSOLE_LOCK) {
          System.out.println("(no sniper has joined yet)");
        }
        continue;
      }

      String body = SOL_VERSION_PREFIX + line;
      sniperChat[0].sendMessage(body);
      synchronized (CONSOLE_LOCK) {
        System.out.println("> sent: " + body);
      }
    }
    connection.disconnect();
  }

  // Every loop turn starts with a blank line then the dangling ">>> "
  // prompt -- if nothing async arrives before the user types, that blank
  // line is what visually separates this turn from the previous one.
  private static void printPrompt() {
    synchronized (CONSOLE_LOCK) {
      System.out.println();
      System.out.print(PROMPT);
      System.out.flush();
    }
  }

  // For an async message that isn't a reply to anything we just sent (the
  // sniper joining, out of the blue while we're idle): clear the dangling
  // prompt only, print the message in its place -- the blank line
  // printPrompt() already put above it stays put as the leading separator
  // -- then leave a trailing blank line before redrawing the prompt.
  private static void printStandaloneAsync(String message) {
    synchronized (CONSOLE_LOCK) {
      System.out.print("\r\033[2K");
      System.out.println(message);
      System.out.println();
      System.out.print(PROMPT);
      System.out.flush();
    }
  }

  // For an async reply that's a direct reaction to the command we just
  // sent (a BID after a PRICE event): also erase the blank line
  // printPrompt() inserted above the dangling prompt, so the reply
  // attaches directly under the "> sent: ..." line instead of leaving a
  // gap, then leave a trailing blank line before redrawing the prompt.
  private static void printAttachedAsync(String message) {
    synchronized (CONSOLE_LOCK) {
      System.out.print("\r\033[2K");
      System.out.print("\033[1A\033[2K");
      System.out.println(message);
      System.out.println();
      System.out.print(PROMPT);
      System.out.flush();
    }
  }

  private static Map<String, String> parseCommand(String messageBody) {
    Map<String, String> fields = new LinkedHashMap<String, String>();
    for (String field : messageBody.split(";")) {
      String trimmed = field.trim();
      if (trimmed.isEmpty()) continue;
      int colonIndex = trimmed.indexOf(':');
      if (colonIndex == -1) continue;
      fields.put(trimmed.substring(0, colonIndex).trim(), trimmed.substring(colonIndex + 1).trim());
    }
    return fields;
  }
}
