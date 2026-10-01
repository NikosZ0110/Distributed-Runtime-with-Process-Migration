package interpreter;

import java.io.Serial;
import java.io.Serializable;

public class FileData implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String fileName;
    private byte[] fileContent;

    public FileData(String fileName, byte[] fileContent) {
        this.fileName = fileName;
        this.fileContent = fileContent;
    }

    public String getFileName() {
        return fileName;
    }

    public byte[] getFileContent() {
        return fileContent;
    }

    @Override
    public String toString() {
        return "FileData{" +
                "fileName='" + fileName + '\'' +
                '}';
    }
}

