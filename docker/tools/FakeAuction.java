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
 * lets you type commands that get sent as chat messages to whichever
 * sniper joined.
 *
 * Usage (inside the test-runner container, on the same classpath as the
 * app + lib/deploy jars):
 *   java FakeAuction <itemId>            # password defaults to "auction"
 *
 * Commands (typed at the prompt once a sniper has joined):
 *   price <currentPrice> <increment> [bidder]   send a PRICE event
 *                                                (bidder defaults to "other bidder";
 *                                                 use "sniper@localhost/Auction" to
 *                                                 simulate the sniper's own bid landing)
 *   close                                        send a CLOSE event
 *   quit                                         disconnect and exit
 */
public class FakeAuction {
  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("usage: java FakeAuction <itemId> [password]");
      System.exit(1);
    }
    String itemId = args[0];
    String password = args.length > 1 ? args[1] : "auction";
    String username = "auction-" + itemId;

    XMPPConnection connection = new XMPPConnection("localhost");
    connection.connect();
    connection.login(username, password, "Auction");
    System.out.println("Logged in as " + username + "@localhost. Waiting for a sniper to join...");

    final Chat[] currentChat = new Chat[1];
    connection.getChatManager().addChatListener(new ChatManagerListener() {
      public void chatCreated(Chat chat, boolean createdLocally) {
        currentChat[0] = chat;
        System.out.println("Sniper joined: " + chat.getParticipant());
        chat.addMessageListener(new MessageListener() {
          public void processMessage(Chat chat, Message message) {
            System.out.println("< received: " + message.getBody());
          }
        });
      }
    });

    Scanner scanner = new Scanner(System.in);
    System.out.println("Commands: price <currentPrice> <increment> [bidder] | close | quit");
    while (true) {
      System.out.print("> ");
      if (!scanner.hasNextLine()) break;
      String line = scanner.nextLine().trim();
      if (line.isEmpty()) continue;

      if (currentChat[0] == null && !line.equals("quit")) {
        System.out.println("(no sniper has joined yet)");
        continue;
      }

      String[] parts = line.split("\\s+");
      switch (parts[0]) {
        case "price": {
          if (parts.length < 3) {
            System.out.println("usage: price <currentPrice> <increment> [bidder]");
            break;
          }
          int currentPrice = Integer.parseInt(parts[1]);
          int increment = Integer.parseInt(parts[2]);
          String bidder = parts.length > 3 ? parts[3] : "other bidder";
          String body = String.format(
              "SOLVersion: 1.1; Event: PRICE; CurrentPrice: %d; Increment: %d; Bidder: %s;",
              currentPrice, increment, bidder);
          currentChat[0].sendMessage(body);
          System.out.println("> sent: " + body);
          break;
        }
        case "close": {
          String body = "SOLVersion: 1.1; Event: CLOSE;";
          currentChat[0].sendMessage(body);
          System.out.println("> sent: " + body);
          break;
        }
        case "quit":
          connection.disconnect();
          return;
        default:
          System.out.println("unknown command: " + parts[0]);
      }
    }
    connection.disconnect();
  }
}
