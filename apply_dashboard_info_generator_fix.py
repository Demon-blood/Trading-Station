#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

GENERATOR_MARKER = "CTS_DASHBOARD_INFO_GENERATOR_FIX_20260822"
KOTLIN_MARKER = "CTS_DASHBOARD_INFO_LOCAL_DIALOG_20260822"

def fail(msg: str) -> None:
    raise SystemExit("[CTS dashboard info generator fix] " + msg)

def encode_for_preview_source(actual: str) -> str:
    # PREVIEW_SOURCE is stored as a single-quoted Python literal containing \n escapes.
    return actual.replace("\\", "\\\\").replace("\n", "\\n").replace("'", "\\'")

def dashboard_state_actual() -> str:
    return "\n".join([
        "    var menuExpanded by remember(currentTab) { mutableStateOf(false) }",
        f"    // {KOTLIN_MARKER}",
        "    var dashboardInfoVisible by remember(currentTab) { mutableStateOf(false) }",
        ""
    ])

def old_button_actual() -> str:
    return "\n".join([
        "                IconButton(onClick = onAction) {",
        '                    Icon(actionIcon, contentDescription = "Action", tint = PreviewText, modifier = Modifier.size(21.dp))',
        "                }"
    ])

def new_button_actual() -> str:
    return "\n".join([
        "                IconButton(",
        "                    onClick = {",
        "                        if (currentTab == AppTab.DASHBOARD) {",
        "                            dashboardInfoVisible = true",
        "                        } else {",
        "                            onAction()",
        "                        }",
        "                    }",
        "                ) {",
        "                    Icon(",
        "                        actionIcon,",
        "                        contentDescription = when (currentTab) {",
        '                            AppTab.DASHBOARD -> "Dashboard information"',
        '                            AppTab.PORTFOLIO, AppTab.POSITIONS, AppTab.ORDERS -> "Refresh"',
        '                            AppTab.SYSTEM_TEST -> "Run system test"',
        '                            else -> "Action"',
        "                        },",
        "                        tint = PreviewText,",
        "                        modifier = Modifier.size(21.dp)",
        "                    )",
        "                }"
    ])

def old_tail_actual() -> str:
    return "\n".join([
        "        Divider(color = PreviewDivider.copy(alpha = 0.55f), thickness = 0.7.dp)",
        "    }",
        "}",
        "",
        "@Composable",
        "fun PreviewBottomNavigation"
    ])

def new_tail_actual() -> str:
    lines = [
        "        Divider(color = PreviewDivider.copy(alpha = 0.55f), thickness = 0.7.dp)",
        "    }",
        "",
        "    if (dashboardInfoVisible) {",
        "        AlertDialog(",
        "            onDismissRequest = { dashboardInfoVisible = false },",
        "            title = {",
        '                Text("Dashboard Information", color = PreviewText, fontWeight = FontWeight.SemiBold)',
        "            },",
        "            text = {",
        "                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {",
        '                    Text("Portfolio Value — current total account valuation reported by the selected PAPER/LIVE provider.", color = PreviewText)',
        '                    Text("24H P/L — realized P/L recorded by CTS in the rolling last 24 hours. It is not all-time P/L and it is not the change from the original PAPER starting balance.", color = PreviewText)',
        '                    Text("Invested — Portfolio Value minus currently available EUR.", color = PreviewText)',
        '                    Text("Available — free EUR reported by the latest portfolio snapshot.", color = PreviewText)',
        '                    Text("24H Volume — CTS trade notional recorded during the rolling last 24 hours; it is not Kraken market-wide trading volume.", color = PreviewText)',
        '                    Text("Active Positions — positions with positive quantity in the latest lifecycle snapshot.", color = PreviewText)',
        '                    Text("Scan refreshes analysis without forcing an order. Execute uses the configured execution path. Start/Stop control the background bot. News opens News & Intelligence.", color = PreviewText)',
        "                }",
        "            },",
        "            confirmButton = {",
        "                Button(onClick = { dashboardInfoVisible = false }) {",
        '                    Text("Close")',
        "                }",
        "            },",
        "            containerColor = PreviewCard",
        "        )",
        "    }",
        "}",
        "",
        "@Composable",
        "fun PreviewBottomNavigation"
    ]
    return "\n".join(lines)

def patch_generated_preview(path: Path) -> bool:
    if not path.exists():
        return False
    text = path.read_text(encoding="utf-8")
    if KOTLIN_MARKER in text:
        return False

    import_anchor = "import androidx.compose.material3.Button\n"
    if "import androidx.compose.material3.AlertDialog\n" not in text:
        if import_anchor not in text:
            fail(f"{path}: Material3 Button import anchor changed")
        text = text.replace(
            import_anchor,
            "import androidx.compose.material3.AlertDialog\n" + import_anchor,
            1
        )

    old_state = "    var menuExpanded by remember(currentTab) { mutableStateOf(false) }\n"
    new_state = dashboard_state_actual()
    if old_state not in text:
        fail(f"{path}: menuExpanded anchor changed")
    text = text.replace(old_state, new_state, 1)

    old_button = old_button_actual()
    if old_button not in text:
        fail(f"{path}: Info IconButton anchor changed")
    text = text.replace(old_button, new_button_actual(), 1)

    old_tail = old_tail_actual()
    if old_tail not in text:
        fail(f"{path}: PreviewAppTopBar tail anchor changed")
    text = text.replace(old_tail, new_tail_actual(), 1)

    path.write_text(text, encoding="utf-8")
    return True

def patch_generator(path: Path) -> bool:
    if not path.exists():
        fail(f"Canonical preview generator missing: {path}")
    text = path.read_text(encoding="utf-8")
    if GENERATOR_MARKER in text:
        return False

    # Add AlertDialog import to embedded PREVIEW_SOURCE.
    import_old = encode_for_preview_source("import androidx.compose.material3.Button\n")
    import_new = encode_for_preview_source(
        "import androidx.compose.material3.AlertDialog\n" +
        "import androidx.compose.material3.Button\n"
    )
    if import_old not in text:
        fail("apply_exact_preview_ui.py: PREVIEW_SOURCE Button import anchor changed")
    text = text.replace(import_old, import_new, 1)

    # Add local dashboard dialog state to PreviewAppTopBar.
    state_old = encode_for_preview_source(
        "    var menuExpanded by remember(currentTab) { mutableStateOf(false) }\n"
    )
    state_new = encode_for_preview_source(dashboard_state_actual())
    if state_old not in text:
        fail("apply_exact_preview_ui.py: PREVIEW_SOURCE menu state anchor changed")
    text = text.replace(state_old, state_new, 1)

    # Make Dashboard handle Info locally instead of delegating to dead MainActivity onAction.
    button_old = encode_for_preview_source(old_button_actual())
    button_new = encode_for_preview_source(new_button_actual())
    if button_old not in text:
        fail("apply_exact_preview_ui.py: PREVIEW_SOURCE Info button anchor changed")
    text = text.replace(button_old, button_new, 1)

    # Add the actual Material3 dialog inside PreviewAppTopBar.
    tail_old = encode_for_preview_source(old_tail_actual())
    tail_new = encode_for_preview_source(new_tail_actual())
    if tail_old not in text:
        fail("apply_exact_preview_ui.py: PREVIEW_SOURCE top-bar tail anchor changed")
    text = text.replace(tail_old, tail_new, 1)

    # Add a plain source marker outside PREVIEW_SOURCE for idempotency/auditing.
    marker_anchor = "# Apply the approved Crypto TradeStation preview UI as the real Compose shell.\n"
    if marker_anchor not in text:
        fail("apply_exact_preview_ui.py: header marker changed")
    text = text.replace(
        marker_anchor,
        marker_anchor + f"# {GENERATOR_MARKER}\n",
        1
    )

    path.write_text(text, encoding="utf-8")
    return True

def main() -> None:
    repo = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd().resolve()
    generator = repo / ".cts-v4-migration/apply_exact_preview_ui.py"
    generator_changed = patch_generator(generator)

    patched = []
    for candidate in [
        repo / "app/src/main/java/com/ksp/cryptobot/PreviewReplicaUi.kt",
        repo / ".cts-v4-migration/app/src/main/java/com/ksp/cryptobot/PreviewReplicaUi.kt",
    ]:
        if patch_generated_preview(candidate):
            patched.append(str(candidate.relative_to(repo)))

    print("[CTS dashboard info generator fix] PASS")
    print(f"generatorChanged={generator_changed}")
    print("generatedCopiesPatched=" + (",".join(patched) if patched else "none"))
    print("The canonical exact-preview generator now owns the working Dashboard Info dialog.")

if __name__ == "__main__":
    main()
