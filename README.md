# A Distributed Runtime System with Process Migration

A distributed runtime system built from scratch for a custom interpreted language. Programs can be started, terminated, and migrated between machines at runtime, allowing execution to resume from the exact point of migration while maintaining program output on the originating machine.

## CLI Usage

This project runs as an interactive Java runtime from the command line. Once started, the user can enter commands directly into the prompt.

### Supported runtime commands

- `run <script-file> [|| <script-file> ...]`
  - Starts a new process group by loading one or more program files.
  - Example:
    - `run multiply.txt <arg1> <arg2>`
    - `run ring.txt <arg1> <arg2> ...`

- `migrate <fromIP> <groupID> <processID> <toIP>`
  - Migrates an active process from one machine to another over the network.
  - Example:
    - `migrate 192.168.1.10 0 1 192.168.1.20`

- `list`
  - Displays all active process groups and their running processes.

- `kill`
  - Shows current TCP connections used for communication between distributed nodes.

- `shutdown`
  - Exits the runtime when there are no active processes.
  - If processes are still running, the runtime warns the user to terminate them first.

Any unrecognized input prints will print an error message:

---

## Script Language

The project includes a custom scripting language used by the programs it executes. Supported instructions include:

- `SET`
- `ADD`, `SUB`, `MUL`, `DIV`, `MOD`
- `BGT`, `BGE`, `BLT`, `BLE`, `BEQ`
- `BRA`
- `PRN`
- `SND`, `RCV`
- `SLP`
- `RET`

These instructions allow scripts to:
- manage variables and arithmetic
- perform branching and looping
- print output
- block execution for a duration
- send and receive messages between processes
- terminate process execution cleanly

SimpleScriptExamples directory contains program examples and a descriptive README file for them.

This runtime is designed for distributed execution, where multiple script processes can run concurrently, communicate across machines, and migrate between hosts while continuing their work.
