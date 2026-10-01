package interpreter;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;

public class Sender {

    Socket socket;
    public final Object lock = new Object();

    public synchronized static void send(String svcID, InetAddress IP, Memory mem, String msg, SNDRCVBlock.Node data, ObjectOutputStream oos) throws IOException {

        String[] comps;

        oos.flush();
        oos.reset();

        switch (svcID) {
            case "MIG":

                MigrationInfoData toSend = new MigrationInfoData(
                    mem.id,
                    mem.groupId,
                    mem.filename,
                    mem.variables,
                    mem.programCounter,
                    mem.parentIP
                );

                // First, send the service identifier
                oos.writeObject("MIG");

                // Secondly, send the source code file
                File file = new File(mem.filename);
                byte[] fileContent = Files.readAllBytes(file.toPath());
                FileData fileData = new FileData(file.getName(), fileContent);
                oos.writeObject(fileData);

                // Finally, send the data class (the instance of the wanted data from the mem instance of Memory class)
                oos.writeObject(toSend);
                break;

            case "PRN":
                // First, send the service ID
                oos.writeObject("PRN");

                // Then, send the message
                oos.writeObject(msg);
                break;

            case "SND":
                // First, send service ID
                oos.writeObject("SND");
                oos.writeObject(data);
                break;

            case "RCV":
                oos.writeObject("RCV");
                oos.writeObject(data);
                break;

            case "RET":
                // First, send service ID
                oos.writeObject("RET");
                comps = msg.split("\\s+");
                oos.writeObject(Integer.parseInt(comps[0])); // comps[0] = groupID
                oos.writeObject(Integer.parseInt(comps[1])); // comps[1] = processID

                break;

            case "CONTINUE":
                oos.writeObject("CONTINUE");
                oos.writeObject(IP);
                comps = msg.split("\\s+");
                oos.writeObject(Integer.parseInt(comps[0])); // comps[0] = groupID
                oos.writeObject(Integer.parseInt(comps[1])); // comps[1] = processID
                break;

            case "OK":
                oos.writeObject("OK");
                comps = msg.split("\\s+");
                oos.writeObject(Integer.parseInt(comps[0])); // comps[0] = groupID
                oos.writeObject(Integer.parseInt(comps[1])); // comps[1] = processID
                break;

            case "NOK":
                oos.writeObject("NOK");
                comps = msg.split("\\s+");
                oos.writeObject(Integer.parseInt(comps[0])); // comps[0] = groupID
                oos.writeObject(Integer.parseInt(comps[1])); // comps[1] = processID
                break;

            default:
                // From code flow in Runtime there is no way default case to be executed
                break;
        }
    }
}
