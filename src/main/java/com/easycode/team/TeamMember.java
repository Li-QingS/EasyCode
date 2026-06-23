package com.easycode.team;

import java.nio.file.Path;

/** 团队成员定义 */
public class TeamMember {
    private final String name;
    private final Path workDir;
    private final RuntimeBackend backend;
    private final boolean requireApproval;
    private volatile MemberStatus status;
    private Path mailboxPath;
    private String sessionId;

    public TeamMember(String name, Path workDir, RuntimeBackend backend, boolean requireApproval) {
        this.name = name;
        this.workDir = workDir;
        this.backend = backend;
        this.requireApproval = requireApproval;
        this.status = MemberStatus.IDLE;
    }

    public String name() { return name; }
    public Path workDir() { return workDir; }
    public RuntimeBackend backend() { return backend; }
    public boolean requireApproval() { return requireApproval; }
    public MemberStatus status() { return status; }
    public Path mailboxPath() { return mailboxPath; }
    public String sessionId() { return sessionId; }

    public void setStatus(MemberStatus s) { this.status = s; }
    public void setMailboxPath(Path p) { this.mailboxPath = p; }
    public void setSessionId(String id) { this.sessionId = id; }

    @Override
    public String toString() {
        return "TeamMember{name='" + name + "', backend=" + backend
            + ", approval=" + requireApproval + ", status=" + status + "}";
    }
}
