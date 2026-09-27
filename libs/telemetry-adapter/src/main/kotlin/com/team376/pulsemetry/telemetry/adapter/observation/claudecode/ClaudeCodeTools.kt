package com.team376.pulsemetry.telemetry.adapter.observation.claudecode

import com.team376.pulsemetry.telemetry.adapter.observation.ToolAction
import com.team376.pulsemetry.telemetry.adapter.observation.ToolOrigin

/** Claude Code 도구의 출처·동작 판정(ADR 0020 부록 A.1). 로그와 스팬이 같은 표를 쓴다 — 근거는 PROFILE-EVIDENCE 5 절. */
internal object ClaudeCodeTools {

	/** MCP 도구 이름 — producer 가 가린 표기(`mcp_tool`)와 `mcp__<서버>__<도구>`. 출처의 근거일 뿐 동작의 근거는 아니다. */
	fun isMcpName(name: String?): Boolean = name == MCP_TOOL || name?.startsWith("mcp__") == true

	/** 출처: 명시 속성(`tool_source` 등) > MCP 이름 > 문서화된 내장 도구 이름. 그 밖은 unknown. */
	fun origin(toolName: String?, declared: String?): ToolOrigin = when {
		declared == "mcp" || isMcpName(toolName) -> ToolOrigin.MCP
		declared == "builtin" || toolName in BUILTIN_TOOLS -> ToolOrigin.BUILTIN
		else -> ToolOrigin.UNKNOWN
	}

	/** 동작: 내장 도구 중 동작이 분명한 이름만. MCP 도구와 그 밖은 unknown. */
	fun action(toolName: String?, mcp: Boolean): ToolAction =
		if (mcp) ToolAction.UNKNOWN else BUILTIN_ACTIONS[toolName] ?: ToolAction.UNKNOWN

	private const val MCP_TOOL = "mcp_tool"

	/** 공식 도구 목록의 내장 도구 이름(근거 문서 5). */
	private val BUILTIN_TOOLS = setOf(
		"Agent", "Artifact", "AskUserQuestion", "Bash", "CronCreate", "CronDelete", "CronList", "Edit", "EndConversation", "EnterPlanMode",
		"EnterWorktree", "ExitPlanMode", "ExitWorktree", "Glob", "Grep", "ListAgents", "ListMcpResourcesTool", "LSP", "Monitor",
		"NotebookEdit", "PowerShell", "PushNotification", "Read", "ReadMcpResourceTool", "RemoteTrigger", "ReportFindings",
		"ScheduleWakeup", "SendFeedback", "SendMessage", "SendUserFile", "ShareOnboardingGuide", "Skill", "SubagentHandback",
		"TaskCreate", "TaskGet", "TaskList", "TaskOutput", "TaskStop", "TaskUpdate", "TodoWrite", "ToolSearch", "WaitForMcpServers",
		"WebFetch", "WebSearch", "Workflow", "Write",
	)

	/** 동작이 분명한 내장 도구. */
	private val BUILTIN_ACTIONS = mapOf(
		"Read" to ToolAction.READ,
		"Write" to ToolAction.WRITE,
		"Edit" to ToolAction.EDIT,
		"NotebookEdit" to ToolAction.EDIT,
		"Glob" to ToolAction.SEARCH,
		"Grep" to ToolAction.SEARCH,
		"WebSearch" to ToolAction.SEARCH,
		"WebFetch" to ToolAction.FETCH,
		"Bash" to ToolAction.EXEC,
		"PowerShell" to ToolAction.EXEC,
	)
}
