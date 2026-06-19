package com.easycode.permission;

public enum PermissionMode {
    DEFAULT, ACCEPT_EDITS, PLAN, BYPASS_PERMISSIONS;

    public PermissionMode next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
