package runtime;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class TCPConnectionEstablisher implements Runnable {
    int ServerPort = 8080;
    ServerSocket socket;

    TCPConnectionEstablisher() throws IOException {
        socket = new ServerSocket(ServerPort);
        Thread thread = new Thread(this);
        thread.start();
    }

    @Override
    public void run() { //Establisher
        while (true) {
            Socket immigrant = null;
            try {
                immigrant = socket.accept();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            System.out.println(immigrant.getInetAddress() + " connected.");;
            Thread immigrantThread = new Thread(new Receiver(immigrant, null));
            immigrantThread.start();
        }
    }
}
