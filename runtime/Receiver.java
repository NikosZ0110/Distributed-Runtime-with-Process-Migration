package runtime;

import interpreter.*;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;

import static runtime.Runtime.*;

public class Receiver implements Runnable {
    Socket immigrant;
    ObjectOutputStream oos;

    public Receiver(Socket immigrantSocket, ObjectOutputStream oos) {
        this.oos = oos;
        immigrant = immigrantSocket;
    }

    @Override
    public void run() {
        try (ObjectInputStream ois = new ObjectInputStream(immigrant.getInputStream())) {
            for (;;) {
                String serviceID = (String) ois.readObject();
                int groupID;
                int processID;
                GroupList tmpEntry;
                SNDRCVBlock.Node justReceived = null;
                SNDRCVBlock.Node data = null;


                switch (serviceID) {
                    case "MIG":
                        FileData fileData = (FileData) ois.readObject();
                        File receivedFile = new File(fileData.getFileName());
                        try (FileOutputStream fos = new FileOutputStream(receivedFile)) {
                            fos.write(fileData.getFileContent());
                        }

                        MigrationInfoData receivedMemoryData = (MigrationInfoData) ois.readObject();
                        Memory mem = new Memory(receivedMemoryData);
                        Runtime.addToGroups(immigrant.getInetAddress(), mem, new ObjectOutputStream(immigrant.getOutputStream()));

                        break;

                    case "PRN":
                        String toPrint = (String) ois.readObject();
                        System.out.println(toPrint);
                        break;

                    case "SND":


                        justReceived = (SNDRCVBlock.Node) ois.readObject();
                        if (Objects.equals(InetAddress.getLocalHost().getHostAddress(), justReceived.parentIP.getHostAddress())) { // eimai o fatha
                            groupsSem.acquire();
                            GroupList tmp = groups.get(justReceived.parentIP);
                            tmp.groupListSem.acquire();
                            for (ExecutingThread group: tmp.groupList) {
                                if (group.threadId != justReceived.threadID) { continue; }
                                group.groupSem.acquire();
                                Memory process = group.getGroup().get(justReceived.receiver);
                                if (process != null) {
                                    if (process.id != -1) {
                                        group.sndrcvBlock.sharedData.add(justReceived);
                                    } else {
                                        activeTCPsSem.acquire();
                                        Sender.send("SND", null, null, null, justReceived, activeTCPs.get(process.currentlyRunningIP).oos);
                                        activeTCPsSem.release();
                                    }
                                } else {
                                    //prepei kill ta panta
                                }
                                group.groupSem.release();
                            }
                            tmp.groupListSem.release();
                            groupsSem.release();
                        } else {
                            groupsSem.acquire();
                            GroupList tmp = groups.get(justReceived.parentIP);
                            tmp.groupListSem.acquire();
                            for (ExecutingThread group : tmp.groupList) {
                                if (group.threadId != justReceived.threadID) {
                                    continue;
                                }
                                group.groupSem.acquire();



                                group.sndrcvBlock.sharedData.add(justReceived);
                                group.groupSem.release();
                            }
                            tmp.groupListSem.release();
                            groupsSem.release();
                        }


                        break;

                    case "RCV":

                        data = (SNDRCVBlock.Node) ois.readObject();
                        if (!Objects.equals(data.parentIP.getHostAddress(), InetAddress.getLocalHost().getHostAddress())) { //den eimai o fatha
                            //ara replace the Node
                            groupsSem.acquire();
                            GroupList tmp = groups.get(data.parentIP);
                            tmp.groupListSem.acquire();
                            for (ExecutingThread group: tmp.groupList) {
                                if (group.threadId != data.threadID) { continue; }
                                group.groupSem.acquire();
                                for (SNDRCVBlock.Node tmpnode: group.sndrcvBlock.sharedData) {



                                    if (tmpnode.sender == data.sender && tmpnode.receiver == data.receiver) {
                                        tmpnode.received = true;
                                    }
                                }
                                group.groupSem.release();
                            }
                            tmp.groupListSem.release();
                            groupsSem.release();
                        } else { //eimai o fatha
                            // prepei na do an o sender einai ston fatha
                            groupsSem.acquire();
                            GroupList tmp2 = groups.get(data.parentIP);
                            tmp2.groupListSem.acquire();
                            for (ExecutingThread group: tmp2.groupList) {
                                if (group.threadId != data.threadID) { continue; }
                                group.groupSem.acquire();
                                Memory tmpMem = group.getGroup().get(data.sender);
                                if (tmpMem.id == -1) {
                                    // dystyxos o sender einai kai aftos immigrant se allo pc
                                    activeTCPsSem.acquire();
                                    Sender.send("RCV", null, null, null, data, activeTCPs.get(tmpMem.currentlyRunningIP).oos);
                                    activeTCPsSem.release();
                                } else {
                                    for (SNDRCVBlock.Node tmpnode: group.sndrcvBlock.sharedData) {
                                        if (tmpnode.sender == data.sender && tmpnode.receiver == data.receiver) {



                                            tmpnode.received = true;
                                        }
                                    }
                                }


                                group.groupSem.release();
                            }
                            tmp2.groupListSem.release();
                            groupsSem.release();
                        }


                        break;

                    case "RET":
                        // Need to remove from roundRobin this process
                        groupID = (int) ois.readObject();
                        processID = (int) ois.readObject();

                        groupsSem.acquire();
                        tmpEntry = groups.get(InetAddress.getByName(InetAddress.getLocalHost().getHostAddress()));
                        tmpEntry.groupListSem.acquire();
                        for (ExecutingThread group: tmpEntry.groupList) {
                            if (group.getThreadId() == groupID) {
                                group.groupSem.acquire();
                                group.getGroup().remove(processID);
                                if (group.allProcessesAreImmigrantsFlag) {
                                    group.allProcessesAreImmigrantsFlag = false;
                                    group.allProcessesAreImmigrantsSem.release();
                                }
                                group.groupSem.release();
                            }
                        }
                        tmpEntry.groupListSem.release();
                        groupsSem.release();

                        // Need to check if there are more activeProcesses with this, else close the connection
                        activeTCPsSem.acquire();
                        activeTCPs.get(immigrant.getInetAddress()).activeProcesses--;
                        if (activeTCPs.get(immigrant.getInetAddress()).activeProcesses == 0) {
                            activeTCPs.get(immigrant.getInetAddress()).socket.close();
                            activeTCPs.remove(immigrant.getInetAddress());
                            activeTCPsSem.release();
                            return;
                        }
                        activeTCPsSem.release();

                        break;

                    case "CONTINUE":
                        InetAddress IPTo = (InetAddress) ois.readObject();
                        groupID = (int) ois.readObject();
                        processID = (int) ois.readObject();
                        // Need to check if I have TCPConnection with new hosting PC (IPTo)
                        activeTCPsSem.acquire();
                        if (!checkTCPExistence(IPTo)) { createNewTCPConnection(IPTo); }
                        activeTCPsSem.release();

                        // Need to update Executing Thread group HashMap to know that hosting PC changes
                        groupsSem.acquire();
                        tmpEntry = groups.get(InetAddress.getByName(InetAddress.getLocalHost().getHostAddress()));
                        tmpEntry.groupListSem.acquire();
                        for (ExecutingThread group: tmpEntry.groupList) {
                            if (group.getThreadId() == groupID) {
                                group.groupSem.acquire();
                                group.getGroup().get(processID).currentlyRunningIP = IPTo;
                                group.groupSem.release();
                            }
                        }
                        tmpEntry.groupListSem.release();
                        groupsSem.release();

                        // Need to send OK to current hosting PC
                        String str = String.valueOf(groupID) + " " + String.valueOf(processID);
                        Sender.send("OK", null, null, str, null, oos);

                        // Need to check if there are more activeProcesses with this, else close the connection
                        activeTCPsSem.acquire();
                        if (activeTCPs.get(immigrant.getInetAddress()).activeProcesses == 0) {
                            activeTCPs.get(immigrant.getInetAddress()).socket.close();
                            activeTCPs.remove(immigrant.getInetAddress());
                        }
                        activeTCPsSem.release();
                        break;

                    case "OK":
                        groupID = (int) ois.readObject();
                        processID = (int) ois.readObject();
                        migBlocksSem.acquire();
                        for (BlockNode blockNode: migBlocks) {
                            if (blockNode.parentIP == immigrant.getInetAddress() &&
                                    blockNode.groupID == groupID &&
                                    blockNode.processID == processID
                            ) {
                                blockNode.block.unblock();
                                migBlocks.remove(blockNode);
                            }
                        }
                        migBlocksSem.release();
                        break;

                    case "NOK":
                        // TODO: AN DOULEVOUN OLA VLEPOUME
                        break;

                    default:
                        // unreachable point of code
                }
            }
        } catch (Exception e) {
            return;
        }
    }
}