package interpreter;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Parser {

    BufferedReader br;

    Parser(Memory memory) throws IOException, CustomException {
        br = new BufferedReader(new FileReader(memory.filename));
        if (!br.readLine().trim().equals("#SIMPLESCRIPT")) { throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + memory.programCounter + ".\n"); }
    }

    public Instruction parse(Memory memory) throws IOException, CustomException {

        Instruction instruction = null;
        String line;
        String[] args;
        boolean labelFound = false;
        int tokenCounter = 0;

        if ((line = br.readLine()) != null) {

            String[] tokens = splitIntoTokens(line);
            if (tokens.length == 0) { memory.programCounter++; return parse(memory); }

            instruction = new Instruction();
            if (tokens[0].charAt(0) == '#') {
                labelFound = true;
                instruction.label = tokens[0];
                try {
                    instruction.operation = tokens[1];
                } catch (ArrayIndexOutOfBoundsException a) {
                    memory.programCounter++;
                    return parse(memory);
                }
                if (!validForm(instruction.label.substring(1))) {
                    throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                }
            } else {
                instruction.operation = tokens[0];
            }

            switch (instruction.operation) {
                case "SET":
                // operation = SET
                // varName = $varName
                // varVal1 = $varName || "string" || int
                // varVal2 = null
                // label = null

                    if (labelFound) {
                        if (tokens.length != 4) { throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n"); }
                        tokenCounter = 2;
                    } else {
                        if (tokens.length != 3) { throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n"); }
                        tokenCounter = 1;
                    }


                    instruction.varName = tokens[tokenCounter++]; // $varName
                    if (instruction.varName.charAt(0) == '$') {
                        if (!validForm(instruction.varName.substring(1))) {
                            throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                        }
                    } else {
                        throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                    }

                    instruction.varVal1 = tokens[tokenCounter]; // $varName ||"string" || int
                    if (instruction.varVal1.charAt(0) == '$') {
                        if (!validForm(instruction.varVal1.substring(1))) {
                            throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                        }
                    } else if (instruction.varVal1.charAt(0) == '"') {
                        if (instruction.varVal1.charAt(instruction.varVal1.length() - 1) != '"') {
                            throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                        }
                    } else {
                        try {
                            Integer.parseInt(instruction.varVal1);
                        } catch (NumberFormatException e) {
                            throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                        }
                    }

                    break;
                case "ADD":
                case "SUB":
                case "MUL":
                case "DIV":
                case "MOD":
                // operation = ADD || SUB || MUL || DIV || MOD
                // varName = $varName
                // varVal1 = $varName || int
                // varVal2 = $varName || int
                // label = null

                    if (labelFound) {
                        if (tokens.length != 5) { throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n"); }
                        tokenCounter = 2;
                    } else {
                        if (tokens.length != 4) { throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n"); }
                        tokenCounter = 1;
                    }

                    instruction.varName = tokens[tokenCounter++]; // $varName
                    if (instruction.varName.charAt(0) == '$') {
                        if (!validForm(instruction.varName.substring(1))) {
                            throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                        }
                    } else {
                        throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                    }

                    instruction.varVal1 = tokens[tokenCounter++]; // $varName || int
                    if (instruction.varVal1.charAt(0) == '$') {
                        if (!validForm(instruction.varVal1.substring(1))) {
                            throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                        }
                    } else {
                        try {
                            Integer.parseInt(instruction.varVal1);
                        } catch (NumberFormatException e) {
                            throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                        }
                    }

                    instruction.varVal2 = tokens[tokenCounter]; // $varName || int
                    if (instruction.varVal2.charAt(0) == '$') {
                        if (!validForm(instruction.varVal2.substring(1))) {
                            throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                        }
                    } else {
                        try {
                            Integer.parseInt(instruction.varVal2);
                        } catch (NumberFormatException e) {
                            throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                        }
                    }

                    break;
                case "BGT":
                case "BGE":
                case "BLT":
                case "BLE":
                case "BEQ":
                // operation = BGT || BGE || BLT || BLE || BEQ
                // varName = null
                // varVal1 = $varName || int
                // varVal2 = $varName || int
                // label = #label

                    if (labelFound) {
                        if (tokens.length != 5) { throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n"); }
                        tokenCounter = 2;
                    } else {
                        if (tokens.length != 4) { throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n"); }
                        tokenCounter = 1;
                    }

                    instruction.varVal1 = tokens[tokenCounter++]; // $varName || int
                    if (instruction.varVal1.charAt(0) == '$') {
                        if (!validForm(instruction.varVal1.substring(1))) {
                            throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                        }
                    } else {
                        try {
                            Integer.parseInt(instruction.varVal1);
                        } catch (NumberFormatException e) {
                            throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                        }
                    }

                    instruction.varVal2 = tokens[tokenCounter++]; // $varName || int
                    if (instruction.varVal2.charAt(0) == '$') {
                        if (!validForm(instruction.varVal2.substring(1))) {
                            throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                        }
                    } else {
                        try {
                            Integer.parseInt(instruction.varVal2);
                        } catch (NumberFormatException e) {
                            throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                        }
                    }

                    instruction.label = tokens[tokenCounter]; // #label
                    if (instruction.label.charAt(0) == '#') {
                        if (!validForm(instruction.label.substring(1))) {
                            throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                        }
                    } else {
                        throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                    }

                    break;
                case "BRA":
                // operation = BRA
                // varName = null
                // varVal1 = null
                // varVal2 = null
                // label = #label

                    if (labelFound) {
                        if (tokens.length != 3) { throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n"); }
                        tokenCounter = 2;
                    } else {
                        if (tokens.length != 2) { throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n"); }
                        tokenCounter = 1;
                    }

                    if (tokens.length != 2) { throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n"); }

                    instruction.label = tokens[tokenCounter]; // #label
                    if (instruction.label.charAt(0) == '#') {
                        if (!validForm(instruction.label.substring(1))) {
                            throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                        }
                    } else {
                        throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                    }

                    break;
                case "SND":
                case "RCV":
                // operation = SND || RCV
                // varName = null
                // varVal1 = $varName || "string" || int
                // varVal2 = $varName || "string" || int + " " + $varName || "string" || int  + " " + ...
                // label = null

                    if (labelFound) {
                        if (tokens.length < 4) { throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n"); }
                        tokenCounter = 2;
                    } else {
                        if (tokens.length < 3) { throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n"); }
                        tokenCounter = 1;
                    }

                    instruction.varVal1 = tokens[tokenCounter]; // $varName || "string" || int
                    if (instruction.varVal1.charAt(0) == '$') {
                        if (!validForm(instruction.varVal1.substring(1))) {
                            throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                        }
                    } else if (instruction.varVal1.charAt(0) == '"') {
                        if (instruction.varVal1.charAt(instruction.varVal1.length() - 1) != '"') {
                            throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                        }
                    } else {
                        try {
                            Integer.parseInt(instruction.varVal1);
                        } catch (NumberFormatException e) {
                            throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                        }
                    }

                    for (int i = tokenCounter; i < tokens.length; i++) {
                        if (tokens[i].charAt(0) == '$') {
                            if (!validForm(tokens[i].substring(1))) {
                                throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                            }
                        } else if (tokens[i].charAt(0) == '"') {
                            if (tokens[i].charAt(tokens[i].length() - 1) != '"') {
                                throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                            }
                        } else {
                            try {
                                Integer.parseInt(tokens[i]);
                            } catch (NumberFormatException e) {
                                throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                            }
                        }
                    }

                    args = new String[tokens.length - 2];
                    System.arraycopy(tokens, 2, args, 0, args.length);
                    instruction.varVal2 = String.join(" ", args); // $varName || "string" || int + " " + $varName || "string" || int  + " " + ...

                    break;
                case "SLP":
                // operation = SLP
                // varName = null
                // varVal1 = $varName || int
                // varVal2 = null
                // label = null

                    if (labelFound) {
                        if (tokens.length != 3) { throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n"); }
                        tokenCounter = 2;
                    } else {
                        if (tokens.length != 2) { throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n"); }
                        tokenCounter = 1;
                    }

                    instruction.varVal1 = tokens[tokenCounter];
                    if (instruction.varVal1.charAt(0) == '$') {
                        if (!validForm(instruction.varVal1.substring(1))) {
                            throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                        }
                    } else {
                        try {
                            Integer.parseInt(instruction.varVal1);
                        } catch (NumberFormatException e) {
                            throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                        }
                    }

                    break;
                case "PRN":
                // operation = PRN
                // varName = null
                // varVal1 = $varName || "string" || int + " " + $varName || "string" || int  + " " + ... || \n
                // varVal2 = null
                // label = null

                    if (labelFound) {
                        if (tokens.length > 2) {
                            for (int i = 2; i < tokens.length; i++) {
                                if (tokens[i].charAt(0) == '$') {
                                    if (!validForm(tokens[i].substring(1))) {
                                        throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                                    }
                                } else if (tokens[i].charAt(0) == '"') {
                                    if (tokens[i].charAt(tokens[i].length() - 1) != '"') {
                                        throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                                    }
                                } else {
                                    try {
                                        Integer.parseInt(tokens[i]);
                                    } catch (NumberFormatException e) {
                                        throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                                    }
                                }
                            }

                            args = new String[tokens.length - 2];
                            System.arraycopy(tokens, 2, args, 0, args.length);
                            instruction.varVal1 = String.join(" ", args); // $varName || "string" || int + " " + $varName || "string" || int  + " " + ...
                            instruction.varVal1 += "\n";

                        } else {
                            instruction.varVal1 = "\n"; // "\n"
                        }
                    } else {
                        if (tokens.length > 1) {
                            for (int i = 1; i < tokens.length; i++) {
                                if (tokens[i].charAt(0) == '$') {
                                    if (!validForm(tokens[i].substring(1))) {
                                        throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                                    }
                                } else if (tokens[i].charAt(0) == '"') {
                                    if (tokens[i].charAt(tokens[i].length() - 1) != '"') {
                                        throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                                    }
                                } else {
                                    try {
                                        Integer.parseInt(tokens[i]);
                                    } catch (NumberFormatException e) {
                                        throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
                                    }
                                }
                            }

                            args = new String[tokens.length - 1];
                            System.arraycopy(tokens, 1, args, 0, args.length);
                            instruction.varVal1 = String.join(" ", args); // $varName || "string" || int + " " + $varName || "string" || int  + " " + ...
                            instruction.varVal1 += "\n";
                        } else {
                            instruction.varVal1 = "\n"; // "\n"
                        }
                    }

                    break;
                case "RET":
                // operation = RET
                // varName = null
                // varVal1 = null
                // varVal2 = null
                // label = null

                    if (labelFound) {
                        if (tokens.length != 2) { throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n"); }
                    } else {
                        if (tokens.length != 1) { throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n"); }
                    }

                    break;
                default:

                    throw new CustomException("Error in program " + memory.filename + " IndentationError: unexpected indent in line: " + ++memory.programCounter + ".\n");
            }
        }

        memory.programCounter++;
        return instruction;
    }

    private boolean validForm(String str) {
        String pattern = "^[a-zA-Z][a-zA-Z0-9]*$";
        return str.matches(pattern);
    }

    public static String[] splitIntoTokens(String str) {
        List<String> tokens = new ArrayList<>();
        String regex = "\"([^\"]*)\"|(\\S+)";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(str);

        while (matcher.find()) {
            if (matcher.group(1) != null) {
                // Quoted string without the quotes
                tokens.add("\"" + matcher.group(1) + "\"");
            } else {
                // Unquoted string
                tokens.add(matcher.group(2));
            }
        }

        // Convert List to Array
        return tokens.toArray(new String[0]);
    }
}
