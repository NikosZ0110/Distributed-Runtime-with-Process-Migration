package runtime;

import interpreter.CustomException;
import interpreter.ExecutingThread;
import interpreter.Sender;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Objects;

import static runtime.Runtime.*;

public class MigrationThread implements Runnable{
    String[] args;

    MigrationThread(String[] tokens) throws CustomException {
        if (tokens.length != 5) { throw new CustomException("migrate: missing operand"); }
        this.args = tokens;

        Thread thread = new Thread(this);
        thread.start();
    }

    @Override
    public void run() {
        try {
            migrate(
                    InetAddress.getByName(args[1]),
                    Integer.parseInt(args[2]),
                    Integer.parseInt(args[3]),
                    InetAddress.getByName(args[4])
            );
        } catch (CustomException | InterruptedException | IOException e) {
            System.out.println(e.getMessage());
        }
    }

    void migrate(InetAddress IPFrom, int groupID, int processID, InetAddress IPTo) throws InterruptedException, IOException, CustomException {
        groupsSem.acquire();
        for (Map.Entry<InetAddress, Runtime.GroupList> entry: groups.entrySet()) { //Check if entry exists
            if (!Objects.equals(entry.getKey().getHostAddress(), IPFrom.getHostAddress())) { continue; }

            //Entry found
            entry.getValue().groupListSem.acquire();
            for (ExecutingThread Thread: entry.getValue().groupList) { //Check if group exists
                if (Thread.getThreadId() != groupID) { continue; }

                //Group found
                Thread.groupSem.acquire();
                for (Map.Entry<Integer, interpreter.Memory> process: Thread.getGroup().entrySet()) { //Check if process exists
                    if (process.getKey() != processID) { continue; }

                    //Process found
                    //Need to send to other PC
                    activeTCPsSem.acquire();
                    if (!checkTCPExistence(IPTo)) { createNewTCPConnection(IPTo); }
                    if (!Objects.equals(IPFrom.getHostAddress(), InetAddress.getLocalHost().getHostAddress())) { //If I am not the parent of the process that is about to migrate
                        activeTCPs.get(IPTo).activeProcesses++;
                        BlockNode blockNode = new BlockNode(IPFrom, groupID, processID);
                        migBlocksSem.acquire();
                        migBlocks.add(blockNode);
                        migBlocksSem.release();
                        String str = String.valueOf(groupID) + " " + String.valueOf(processID);
                        Sender.send("CONTINUE", IPTo, null, str, null, entry.getValue().oos);
                        blockNode.block.block();
                        //wait response from parent that he has established connection with destination pc
                        Sender.send("MIG", null, process.getValue(), null, null, activeTCPs.get(IPTo).oos);
                        Thread.getGroup().remove(processID);
                        activeTCPs.get(IPTo).activeProcesses--;
                        if (activeTCPs.get(IPTo).activeProcesses == 0) {
                            activeTCPs.get(IPTo).socket.close();
                            activeTCPs.remove(IPTo);
                        }
                    } else {
                        Sender.send("MIG", null, process.getValue(), null, null, activeTCPs.get(IPTo).oos);
                        process.getValue().id = -1;
                        process.getValue().variables = null;
                        process.getValue().parser = null;
                        process.getValue().currentlyRunningIP = IPTo;
                        activeTCPs.get(IPTo).activeProcesses++;
                    }


                    activeTCPsSem.release();

                    //need to stop processID execution from this pc
                    Thread.groupSem.release();
                    entry.getValue().groupListSem.release();
                    groupsSem.release();
                    return;
                }

                Thread.groupSem.release();
                entry.getValue().groupListSem.release();
                groupsSem.release();
                throw new CustomException("Migration failed. No such processID.");
            }

            entry.getValue().groupListSem.release();
            groupsSem.release();
            throw new CustomException("Migration failed. No such groupID.");
        }

        groupsSem.release();
        throw new CustomException("Migration failed. No such entry.");
    }
}
