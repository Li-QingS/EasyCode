package com.easycode.permission;

import java.nio.file.Path;

public record PermissionContext(PermissionMode mode, RuleEngine ruleEngine, Path projectRoot) {}
