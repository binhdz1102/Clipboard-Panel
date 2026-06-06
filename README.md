# Clipboard Panel

Clipboard Panel is a simple desktop tool that helps you quickly copy predefined text templates to the clipboard.

## Features

- Display templates as buttons in a grid layout.
- Single-click a button to copy its template content.
- Double-click a button to edit or delete the template.
- Add new templates using the Add button.
- Automatically save templates to `templates.json`.
- Reload templates from file at any time.
- Restore all templates when the application is reopened.

## Example

Template Name:

```text
Ticket Report
```

Template Content:

```text
Problem:

Root Cause:

Corrective Action:

Ticket Number:
```

Clicking the **Ticket Report** button will automatically copy the template to the clipboard, ready to paste anywhere.

## Data Storage

All templates are stored in:

```text
templates.json
```

## Run

```bash
pythonw clipboard_panel.pyw
```

or

```bash
python clipboard_panel.py
```

## Requirements

- Python 3.10+
- Tkinter

---

**Clipboard Panel** provides a fast and convenient way to manage and reuse frequently used text templates.