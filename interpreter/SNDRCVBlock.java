package interpreter;

import java.io.Serializable;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public class SNDRCVBlock implements Serializable {
    private static final long serialVersionUID = 1L;

    public List<Node> sharedData = new ArrayList<>();

    public static class Node implements Serializable {
        private static final long serialVersionUID = 1L;

        public int sender;
        public int receiver;
        public final InetAddress parentIP;
        public final int threadID;
        public boolean received = false;
        public List<Variables> data;

        Node(int sender, int receiver, InetAddress parentIP, int threadID) {
            this.sender = sender;
            this.receiver = receiver;
            this.parentIP = parentIP;
            this.threadID = threadID;
            data = new ArrayList<>();
        }
    }
}
