/// Start a session, print the connect address, then stop.
///
///   FISPROXY_API_TOKEN   required
///   FISPROXY_API_BASE    default https://api.fisproxy.org
///
/// Run from the repository root after `mvn -q -DskipTests package`:
///
///   javac -cp target/fisproxy-0.1.0.jar examples/SessionExample.java
///   java -cp target/fisproxy-0.1.0.jar:examples SessionExample

import org.fisproxy.Client;
import org.fisproxy.Entrance;
import org.fisproxy.Operation;
import org.fisproxy.SessionStatus;
import org.fisproxy.StopResult;
import org.fisproxy.UserProfile;

public final class SessionExample {
    public static void main(String[] args) {
        try (Client client = Client.fromEnv()) {
            UserProfile me = client.me();
            System.out.println("user " + me.id() + " service_point " + me.balances().servicePoint());

            Operation operation = client.start();
            System.out.println("start " + operation.status() + " session " + operation.sessionId());

            SessionStatus status = client.status();
            System.out.println("running " + status.running() + " address " + status.address());
            for (Entrance entrance : status.entrances()) {
                System.out.println("entrance " + entrance.name() + " " + entrance.address());
            }

            StopResult stopped = client.stop();
            System.out.println("stop duration " + stopped.duration() + " deduction " + stopped.deduction());
        }
    }
}
