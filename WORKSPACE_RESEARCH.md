# 🔬 LastChat Workspace Architecture & Implementation Guide

> **Source Analysis**: Researched from `E:\BIT\scratch\LastChat` (`:workspace` library, `:app` data layer, AI tools, UI, and native PTY).

---

## 📑 Table of Contents
1. [Overview & Purpose](#1-overview--purpose)
2. [Module & System Architecture](#2-module--system-architecture)
3. [Filesystem Layout & Bind Mounts](#3-filesystem-layout--bind-mounts)
4. [The PRoot Execution Engine](#4-the-proot-execution-engine)
5. [Rootfs Distribution & Patching Engine](#5-rootfs-distribution--patching-engine)
6. [AI Agent Tools & Prompt Injection](#6-ai-agent-tools--prompt-injection)
7. [Storage Access Framework (SAF) Provider](#7-storage-access-framework-saf-provider)
8. [Native Pseudo-Terminal (PTY) & Terminal UI](#8-native-pseudo-terminal-pty--terminal-ui)
9. [Integration Guide for BIT](#9-integration-guide-for-bit)

---

## 1. Overview & Purpose

In LastChat, a **Workspace** is an isolated, on-device Linux container running on top of **PRoot** without requiring root permissions. 

It provides:
- **On-Device Code Execution**: Run Python, Node.js, C/C++, Bash scripts directly on Android hardware.
- **Persistent AI Working Directory**: The LLM agent can write, edit, compile, execute, and verify code across conversation turns.
- **APT / Alpine Package Ecosystem**: Full package manager support (`apt-get install`, `apk add`) for developer tools.
- **SAF Integration**: Standard Android file managers and desktop USB connections can browse and manage workspace files directly.
- **Interactive Terminal**: An xterm-compatible terminal view embedded inside the app.

---

## 2. Module & System Architecture

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                       App UI Layer                                     │
│  • WorkspacePage.kt             • WorkspaceDetailPage.kt    • WorkspaceTerminalPage.kt │
│  • WorkspaceViewModel.kt        • WorkspaceDetailVM.kt       • WorkspaceTerminalSession │
└───────────────────────────────────────────┬────────────────────────────────────────────┘
                                            │
┌───────────────────────────────────────────▼────────────────────────────────────────────┐
│                                App Data & Agent Layer                                  │
│  • WorkspaceRepository.kt       • WorkspaceDAO / Entity     • WorkspaceDocumentsProvider│
│  • WorkspaceTools.kt            • WorkspaceReminderTransformer.kt (Prompt & Attachments)│
└───────────────────────────────────────────┬────────────────────────────────────────────┘
                                            │
┌───────────────────────────────────────────▼────────────────────────────────────────────┐
│                                   :workspace Module                                    │
│  • WorkspaceManager.kt          • WorkspaceFileSystem.kt    • ProotRuntime.kt          │
│  • RootfsInstaller.kt           • RootfsPatcher.kt          • ProotShellRunner.kt      │
│  • termux_pty.cpp (Native JNI)  • libproot_exec.so          • libproot_loader.so       │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Filesystem Layout & Bind Mounts

Each workspace is isolated inside its own UUID directory:

```
/data/user/0/<package>/files/workspaces/<workspace-id>/
├── files/               <── Mounted inside Linux as `/workspace` (User & Agent working dir)
│   ├── uploads/         <── Auto-synced chat media and document attachments
│   ├── main.py
│   └── output.png
├── linux/               <── Rootfs (Ubuntu, Alpine, or Debian root `/`)
│   ├── bin/, etc/, usr/, lib/, root/, var/
├── tmp/                 <── Bound to `/tmp` (PRoot sockets, memfd, ashmem)
└── proot-launch.txt     <── Persisted successful PRoot mode (e.g. no-memfd, no-seccomp)
```

### 🔗 Sandbox Bind Mounts Table
| Host Path | Guest Sandbox Path | Purpose |
| :--- | :--- | :--- |
| `<workspace>/linux` | `/` | Root filesystem |
| `<workspace>/files` | `/workspace` | Persistent user project workspace |
| `<appDir>/files/skills` | `/skills` | Progressive disclosure agent skills |
| `/dev`, `/proc`, `/sys` | `/dev`, `/proc`, `/sys` | Android kernel system mounts |
| `<workspace>/tmp` | `/tmp` | Temporary execution & sockets |

---

## 4. The PRoot Execution Engine

PRoot uses `ptrace` system call interception to translate system calls (such as `chroot`, `mount`, file paths, and UID/GIDs) in user space.

### 🛡️ Adaptive Compatibility Matrix (`ProotLaunchModes.kt`)
Android kernels across OEM vendors (Xiaomi, Samsung, Transsion/Infinix, Pixel) enforce varying `seccomp` and `memfd` restrictions. LastChat tests launch modes sequentially until one succeeds:

1. **`no-memfd` (Default Modern Android)**:
   - Sets `--ashmem-memfd` flag (uses Android ashmem driver instead of `memfd_create`).
   - Env: `PROOT_NO_SECCOMP=1`, `PROOT_ASSUME_NEW_SECCOMP=1`, `PROOT_ASSUME_MEMFD_UNSUPPORTED=1`.
2. **`no-memfd-minimal`**:
   - Omits `--link2symlink` and sets `PROOT_IGNORE_MISSING_BINDINGS=1`.
3. **`default`**:
   - Standard PRoot flags (`--root-id`, `--link2symlink`, `--kill-on-exit`).
4. **`no-seccomp` / `compat`**:
   - Bypasses seccomp filtering; sets `PROOT_FORCE_KOMPAT=1`.

Once validated, the working combination is stored in `proot-launch.txt` for instantaneous future executions.

### 🔍 Binary Architecture Matching
- Dynamically parses the ELF header machine code (ELF Machine 183 = `ARM64`, 62 = `AMD64`, 40 = `ARMHF`) to prevent loading mismatched rootfs architectures.

---

## 5. Rootfs Distribution & Patching Engine

### 📦 Rootfs Installation (`RootfsInstaller.kt`)
- Streams download of `.tar.xz` or `.tar.gz` rootfs archives using `XZInputStream` / `GZIPInputStream`.
- Extracts POSIX tar entries directly into a staging directory before atomic rename.

### 🛠️ Android-Specific Rootfs Patching (`RootfsPatcher.kt`)
Standard Linux distributions cannot run out-of-the-box in PRoot on Android without specific adjustments:

1. **DNS Resolution (`/etc/resolv.conf`)**:
   - Replaces unresolvable local systemd-resolved stubs with reliable public resolvers (`1.1.1.1`, `8.8.8.8`, `223.5.5.5`).
2. **Android Supplementary GID Mapping (`/etc/group`)**:
   - Reads the app's supplementary group IDs from `/proc/self/status` (e.g. `inet`, `sdcard_rw`) and injects them as `android_gid_<id>` so network sockets work without permission errors.
3. **APT & DPKG Workarounds**:
   - `/etc/dpkg/dpkg.cfg.d/force-unsafe-io`: Prevents slow `sync()` calls on flash storage.
   - `/usr/sbin/policy-rc.d` (`exit 101`): Stops apt-get from attempting to start system daemons during package installs.
   - `/etc/apt/apt.conf.d/99proot-sandbox`: Configures `APT::Sandbox::User "root";` so apt does not drop to nonexistent `_apt` user.
4. **Merged `/usr` Symlinks**:
   - Guarantees symlinks `/bin -> usr/bin`, `/sbin -> usr/sbin`, `/lib -> usr/lib` remain consistent across Android private storage.

---

## 6. AI Agent Tools & Prompt Injection

### 🛠️ Registered Workspace Tools (`WorkspaceTools.kt`)
| Tool Name | Parameters | Purpose |
| :--- | :--- | :--- |
| `workspace_shell` | `command`, `cwd`, `timeout` | Executes arbitrary shell commands inside the sandbox |
| `workspace_read_file` | `path` | Reads text from `/workspace` or rootfs |
| `workspace_write_file` | `path`, `text`, `overwrite` | Atomically writes text files |
| `workspace_edit_file` | `path`, `old_text`, `new_text`, `replace_all` | Precise text replacement |

### 🔒 Granular Security Approvals
- Each workspace maintains tool approval overrides in Room database.
- By default, `workspace_shell` requires user approval before execution, while read/write tools execute automatically.

### 💬 System Prompt Injection (`WorkspaceReminderTransformer.kt`)
When a conversation has an active workspace, the system prompt is automatically augmented:
```xml
<workspace>
You have access to a persistent Linux workspace named "DevLab", running in a sandboxed proot rootfs environment.
- The workspace files area is mounted at `/workspace`. Use it as your working directory; files written there persist across turns.
- Available tools: `workspace_read_file`, `workspace_write_file`, `workspace_edit_file`, `workspace_shell`.
- The skills directory is mounted at `/skills`.
- Current working directory: `/workspace`.
</workspace>
```
Any user attachments (images, PDFs, source code) uploaded to the chat are mirrored to `/workspace/uploads/` and declared in the prompt context.

---

## 7. Storage Access Framework (SAF) Provider

`WorkspaceDocumentsProvider.kt` registers a standard Android `DocumentsProvider`:
- **Document Authority**: `<package_name>.workspace.documents`
- **Root**: Displays all workspaces as top-level folders.
- **Children**: Exposes the `files/` directory of each workspace.
- **Capabilities**: Enables Android's native Files app, file pickers, and third-party explorers to create, read, edit, delete, and copy workspace files directly.

---

## 8. Native Pseudo-Terminal (PTY) & Terminal UI

### 🖥️ Native C++ PTY (`termux_pty.cpp`)
- Calls `posix_openpt(O_RDWR | O_CLOEXEC)`, `grantpt()`, and `unlockpt()`.
- Forks subprocess and assigns slave PTY to `STDIN_FILENO`, `STDOUT_FILENO`, and `STDERR_FILENO`.
- Sets terminal window size via `ioctl(fd, TIOCSWINSZ, &size)`.

### 🎨 Compose Terminal Page (`WorkspaceTerminalPage.kt`)
- Integrates `TerminalView` and `TerminalSession` from Termux terminal emulator.
- Full 256-color palette, ANSI styling, gesture scrolling, copy/paste selection, and virtual modifier bar (Ctrl, Alt, Tab, Esc).

---

## 9. Integration Guide for BIT

To bring on-device Linux workspaces into BIT:
1. **Include `:workspace` module**: Add `workspace/` module with CMake configuration and PRoot binaries.
2. **Add Room Entity**: Create `WorkspaceEntity` and `WorkspaceDAO` in BIT's database schema.
3. **Register Agent Tools**: Connect `WorkspaceTools` with BIT's LLM tool registry (`ToolCallingEngine` / `GGUFEngine`).
4. **SAF Provider**: Add `WorkspaceDocumentsProvider` to `AndroidManifest.xml`.
5. **UI Navigation**: Add `WorkspacePage`, `WorkspaceDetailPage`, and `WorkspaceTerminalPage` under Settings / Developer tools.
