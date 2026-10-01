package interpreter;

import java.io.Serializable;
import java.net.InetAddress;
import java.util.List;

public class MigrationInfoData implements Serializable {
    private static final long serialVersionUID = 1L;

    int processID;
    int groupID;
    public String filename;
    List<Variables> variables;
    int programCounter;
    InetAddress parentIP;

    public MigrationInfoData(int              processID,
                             int              groupID,
                             String           filename,
                             List<Variables>  variables,
                             int              programCounter,
                             InetAddress      parentIP) {
        this.processID = processID;
        this.groupID = groupID;
        this.filename = filename;
        this.variables = variables;
        this.programCounter = programCounter;
        this.parentIP = parentIP;
    }
}