import json
import time
import tkinter as tk
from dataclasses import asdict, dataclass
from pathlib import Path
from tkinter import messagebox
from typing import Optional
from uuid import uuid4

APP_TITLE = "Clipboard Panel"
DATA_FILE = Path(__file__).with_name("templates.json")
ADD_ICON_FILE = Path(__file__).with_name("add_button.png")

# Add default templates here if you want the app to start with built-in data.
DEFAULT_TEMPLATES = []
# Example:
# DEFAULT_TEMPLATES = [
#     {
#         "id": "ticket-report",
#         "title": "ticket report",
#         "template": "Problem:\n\nRoot cause:\n\nCorrective action:\n\nTicket number:\n",
#     }
# ]


@dataclass
class TemplateItem:
    id: str
    title: str
    template: str


class TemplateDialog(tk.Toplevel):
    def __init__(
        self,
        master: tk.Tk,
        title: str,
        initial_title: str = "",
        initial_template: str = "",
        show_delete: bool = False,
        on_save=None,
        on_delete=None,
    ):
        super().__init__(master)
        self.title(title)
        self.resizable(True, True)
        self.minsize(520, 420)
        self.transient(master)
        self.grab_set()

        self.on_save = on_save
        self.on_delete = on_delete

        container = tk.Frame(self, padx=16, pady=16)
        container.pack(fill="both", expand=True)

        tk.Label(container, text="Display name", anchor="w").pack(fill="x")
        self.title_entry = tk.Entry(container)
        self.title_entry.pack(fill="x", pady=(4, 12))
        self.title_entry.insert(0, initial_title)

        tk.Label(container, text="Template text", anchor="w").pack(fill="x")

        text_frame = tk.Frame(container)
        text_frame.pack(fill="both", expand=True, pady=(4, 12))

        self.template_text = tk.Text(text_frame, wrap="word", undo=True)
        self.template_text.pack(side="left", fill="both", expand=True)
        self.template_text.insert("1.0", initial_template)

        scrollbar = tk.Scrollbar(text_frame, command=self.template_text.yview)
        scrollbar.pack(side="right", fill="y")
        self.template_text.configure(yscrollcommand=scrollbar.set)

        button_row = tk.Frame(container)
        button_row.pack(fill="x")

        if show_delete:
            delete_btn = tk.Button(
                button_row,
                text="Delete",
                command=self._delete,
                bg="#ffdddd",
                activebackground="#ffcccc",
            )
            delete_btn.pack(side="left")

        cancel_btn = tk.Button(button_row, text="Cancel", command=self.destroy)
        cancel_btn.pack(side="right", padx=(8, 0))

        save_btn = tk.Button(button_row, text="Save", command=self._save)
        save_btn.pack(side="right")

        self.bind("<Escape>", lambda _event: self.destroy())
        self.bind("<Control-s>", lambda _event: self._save())

        self.title_entry.focus_set()
        self._center_on_parent(master)

    def _center_on_parent(self, parent: tk.Tk):
        self.update_idletasks()
        parent_x = parent.winfo_rootx()
        parent_y = parent.winfo_rooty()
        parent_w = parent.winfo_width()
        parent_h = parent.winfo_height()
        w = self.winfo_width()
        h = self.winfo_height()
        x = parent_x + max((parent_w - w) // 2, 0)
        y = parent_y + max((parent_h - h) // 2, 0)
        self.geometry(f"+{x}+{y}")

    def _save(self):
        title = self.title_entry.get().strip()
        template = self.template_text.get("1.0", "end-1c")

        if not title:
            messagebox.showwarning("Missing name", "Please enter a display name for this template.")
            return
        if not template.strip():
            messagebox.showwarning("Missing template", "Please enter template text.")
            return

        if self.on_save:
            self.on_save(title, template)
        self.destroy()

    def _delete(self):
        if messagebox.askyesno("Delete template", "Are you sure you want to delete this template?"):
            if self.on_delete:
                self.on_delete()
            self.destroy()


class TemplateClipboardApp:
    DEFAULT_GRID_COLUMNS = 4
    MAX_GRID_COLUMNS = 8
    BUTTON_SIZE = 80
    CELL_GAP = 12
    SINGLE_CLICK_DELAY_MS = 280

    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title(APP_TITLE)
        self.root.geometry("720x520")
        self.root.minsize(620, 420)

        self.templates: list[TemplateItem] = self._load_templates()
        self.add_icon: Optional[tk.PhotoImage] = self._load_add_icon()

        self.pending_click_after_id: Optional[str] = None
        self.last_click_template_id: Optional[str] = None
        self.last_click_time = 0.0
        self.current_grid_columns = self.DEFAULT_GRID_COLUMNS
        self.resize_after_id: Optional[str] = None

        self._build_ui()
        self._render_grid()

    def _build_ui(self):
        root_frame = tk.Frame(self.root, padx=12, pady=12)
        root_frame.pack(fill="both", expand=True)

        header = tk.Frame(root_frame)
        header.pack(fill="x", pady=(0, 10))

        tk.Label(
            header,
            text=APP_TITLE,
            font=("Segoe UI", 15, "bold"),
            anchor="w",
        ).pack(side="left")

        tk.Button(header, text="Reload", command=self._reload_from_disk).pack(side="right")

        help_text = "Single-click to copy. Double-click to edit or delete. Use + to add a new template."
        tk.Label(root_frame, text=help_text, anchor="w", fg="#555555").pack(fill="x", pady=(0, 8))

        canvas_frame = tk.Frame(root_frame)
        canvas_frame.pack(fill="both", expand=True)

        self.canvas = tk.Canvas(canvas_frame, highlightthickness=0)
        self.canvas.pack(side="left", fill="both", expand=True)

        scrollbar = tk.Scrollbar(canvas_frame, orient="vertical", command=self.canvas.yview)
        scrollbar.pack(side="right", fill="y")
        self.canvas.configure(yscrollcommand=scrollbar.set)

        self.grid_frame = tk.Frame(self.canvas)
        self.canvas_window = self.canvas.create_window((0, 0), window=self.grid_frame, anchor="nw")

        self.grid_frame.bind("<Configure>", self._on_grid_configure)
        self.canvas.bind("<Configure>", self._on_canvas_configure)

        self.status_var = tk.StringVar(value=f"Data file: {DATA_FILE}")
        tk.Label(root_frame, textvariable=self.status_var, anchor="w", fg="#2f6f2f").pack(fill="x", pady=(8, 0))

    def _on_grid_configure(self, _event):
        self.canvas.configure(scrollregion=self.canvas.bbox("all"))

    def _on_canvas_configure(self, event):
        self.canvas.itemconfigure(self.canvas_window, width=event.width)

        new_columns = self._calculate_grid_columns(event.width)
        if new_columns != self.current_grid_columns:
            self.current_grid_columns = new_columns
            if self.resize_after_id is not None:
                self.root.after_cancel(self.resize_after_id)
            self.resize_after_id = self.root.after(80, self._render_grid)

    def _calculate_grid_columns(self, available_width: int | None = None) -> int:
        width = available_width or self.canvas.winfo_width() or self.root.winfo_width()
        cell_width = self.BUTTON_SIZE + self.CELL_GAP
        columns = max(1, width // cell_width)
        return min(columns, self.MAX_GRID_COLUMNS)

    def _load_add_icon(self) -> Optional[tk.PhotoImage]:
        if not ADD_ICON_FILE.exists():
            return None
        try:
            image = tk.PhotoImage(file=str(ADD_ICON_FILE))
            return image.subsample(8, 8)
        except tk.TclError:
            return None

    def _load_templates(self) -> list[TemplateItem]:
        if not DATA_FILE.exists():
            return [TemplateItem(**item) for item in DEFAULT_TEMPLATES]

        try:
            with DATA_FILE.open("r", encoding="utf-8") as f:
                raw = json.load(f)
        except (json.JSONDecodeError, OSError) as exc:
            messagebox.showerror("JSON read error", f"Could not read templates.json:\n{exc}")
            return []

        raw_items = raw.get("templates", raw) if isinstance(raw, dict) else raw
        if not isinstance(raw_items, list):
            return []

        items: list[TemplateItem] = []
        for item in raw_items:
            if not isinstance(item, dict):
                continue
            title = str(item.get("title", "")).strip()
            template = str(item.get("template", ""))
            if title and template:
                items.append(
                    TemplateItem(
                        id=str(item.get("id") or uuid4()),
                        title=title,
                        template=template,
                    )
                )
        return items

    def _save_templates(self):
        data = {"templates": [asdict(item) for item in self.templates]}
        with DATA_FILE.open("w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)

    def _reload_from_disk(self):
        self.templates = self._load_templates()
        self._render_grid()
        self.status_var.set("Reloaded templates.json")

    def _render_grid(self):
        self.resize_after_id = None

        for child in self.grid_frame.winfo_children():
            child.destroy()

        columns = self.current_grid_columns
        for col in range(self.MAX_GRID_COLUMNS):
            self.grid_frame.grid_columnconfigure(col, minsize=0, weight=0)
        for row in range(100):
            self.grid_frame.grid_rowconfigure(row, minsize=0, weight=0)

        for col in range(columns):
            self.grid_frame.grid_columnconfigure(
                col,
                minsize=self.BUTTON_SIZE + self.CELL_GAP,
                weight=0,
                uniform="template_button_column",
            )

        total_items = len(self.templates) + 1
        total_rows = (total_items + columns - 1) // columns
        for row in range(total_rows):
            self.grid_frame.grid_rowconfigure(
                row,
                minsize=self.BUTTON_SIZE + self.CELL_GAP,
                weight=0,
                uniform="template_button_row",
            )

        for index, item in enumerate(self.templates):
            row = index // columns
            col = index % columns
            self._create_template_button(item, row, col)

        add_index = len(self.templates)
        add_row = add_index // columns
        add_col = add_index % columns
        self._create_add_button(add_row, add_col)

    def _create_square_cell(self, row: int, col: int) -> tk.Frame:
        cell = tk.Frame(
            self.grid_frame,
            width=self.BUTTON_SIZE,
            height=self.BUTTON_SIZE,
        )
        cell.grid(row=row, column=col, padx=self.CELL_GAP // 2, pady=self.CELL_GAP // 2)
        cell.grid_propagate(False)
        cell.pack_propagate(False)
        return cell

    def _create_template_button(self, item: TemplateItem, row: int, col: int):
        cell = self._create_square_cell(row, col)
        btn = tk.Button(
            cell,
            text=item.title,
            wraplength=self.BUTTON_SIZE - 18,
            justify="center",
            relief="raised",
            bg="#f4f7fb",
            activebackground="#e4ecfb",
            padx=4,
            pady=4,
        )
        btn.place(x=0, y=0, width=self.BUTTON_SIZE, height=self.BUTTON_SIZE)
        btn.bind("<ButtonRelease-1>", lambda _event, current=item: self._handle_template_click(current))

    def _create_add_button(self, row: int, col: int):
        cell = self._create_square_cell(row, col)
        kwargs = {
            "relief": "raised",
            "bg": "#eef7ee",
            "activebackground": "#ddf0dd",
            "command": self._open_add_dialog,
        }
        if self.add_icon is not None:
            kwargs["image"] = self.add_icon
        else:
            kwargs["text"] = "+"
            kwargs["font"] = ("Segoe UI", 26, "bold")

        btn = tk.Button(cell, **kwargs)
        btn.place(x=0, y=0, width=self.BUTTON_SIZE, height=self.BUTTON_SIZE)

    def _handle_template_click(self, item: TemplateItem):
        now = time.monotonic()
        is_double_click = (
            self.last_click_template_id == item.id
            and now - self.last_click_time <= self.SINGLE_CLICK_DELAY_MS / 1000 * 1.5
        )

        if is_double_click:
            if self.pending_click_after_id is not None:
                self.root.after_cancel(self.pending_click_after_id)
                self.pending_click_after_id = None
            self.last_click_template_id = None
            self.last_click_time = 0.0
            self._open_edit_dialog(item)
            return

        if self.pending_click_after_id is not None:
            self.root.after_cancel(self.pending_click_after_id)

        self.last_click_template_id = item.id
        self.last_click_time = now
        self.pending_click_after_id = self.root.after(
            self.SINGLE_CLICK_DELAY_MS,
            lambda current=item: self._copy_template(current),
        )

    def _copy_template(self, item: TemplateItem):
        self.pending_click_after_id = None
        self.last_click_template_id = None
        self.last_click_time = 0.0

        self.root.clipboard_clear()
        self.root.clipboard_append(item.template)
        self.root.update()
        self.status_var.set(f"Copied template: {item.title}")

    def _open_add_dialog(self):
        TemplateDialog(
            self.root,
            title="Add Template",
            initial_title="",
            initial_template="",
            show_delete=False,
            on_save=self._add_template,
        )

    def _open_edit_dialog(self, item: TemplateItem):
        TemplateDialog(
            self.root,
            title=f"Edit Template: {item.title}",
            initial_title=item.title,
            initial_template=item.template,
            show_delete=True,
            on_save=lambda title, template: self._update_template(item.id, title, template),
            on_delete=lambda: self._delete_template(item.id),
        )

    def _add_template(self, title: str, template: str):
        self.templates.append(TemplateItem(id=str(uuid4()), title=title, template=template))
        self._save_templates()
        self._render_grid()
        self.status_var.set(f"Added template: {title}")

    def _update_template(self, template_id: str, title: str, template: str):
        for item in self.templates:
            if item.id == template_id:
                item.title = title
                item.template = template
                break
        self._save_templates()
        self._render_grid()
        self.status_var.set(f"Updated template: {title}")

    def _delete_template(self, template_id: str):
        before = len(self.templates)
        self.templates = [item for item in self.templates if item.id != template_id]
        if len(self.templates) != before:
            self._save_templates()
            self._render_grid()
            self.status_var.set("Deleted template")


def main():
    root = tk.Tk()
    TemplateClipboardApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
