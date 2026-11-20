"""Окно подготовки доступа на Windows."""

from queue import Empty, Queue
from threading import Thread
import tkinter as tk
from tkinter import simpledialog, ttk


def prepare_window(prepare, initial_address):
    root = tk.Tk()
    root.title("Подготовка доступа к свету")
    root.resizable(False, False)
    frame = ttk.Frame(root, padding=18)
    frame.grid()
    ttk.Label(frame, text="Wiren Board Cloud", font=("Segoe UI", 15, "bold")).grid(
        row=0, column=0, columnspan=3, sticky="w"
    )
    ttk.Label(frame, text="Подготовить доступ можно до подключения часов.\nПароль используется только для входа в WB Cloud.").grid(
        row=1, column=0, columnspan=3, sticky="w", pady=(6, 12)
    )

    variables = [tk.StringVar(root) for _ in range(4)]
    variables[3].set(initial_address)
    entries = []
    for row, (label, value, mask) in enumerate(zip(
        ("Почта WB Cloud", "Пароль", "Код 2FA, если настроен", "IP для PuTTY, если подключён"),
        variables,
        ("", "*", "*", ""),
    ), start=2):
        ttk.Label(frame, text=label).grid(row=row, column=0, sticky="w", pady=4, padx=(0, 10))
        entry = ttk.Entry(frame, textvariable=value, show=mask, width=31)
        entry.grid(row=row, column=1, columnspan=2, sticky="ew", pady=4)
        entries.append(entry)

    show_password = tk.BooleanVar(root)
    password = entries[1]
    ttk.Checkbutton(frame, text="Показать пароль", variable=show_password,
                    command=lambda: password.config(show="" if show_password.get() else "*")).grid(
        row=6, column=1, sticky="w"
    )
    paste = ttk.Button(frame, text="Вставить", command=lambda: password.event_generate("<<Paste>>"))
    paste.grid(row=6, column=2, sticky="e")

    status = tk.StringVar(root)
    ttk.Label(frame, textvariable=status, wraplength=450).grid(
        row=7, column=0, columnspan=3, sticky="w", pady=(10, 5)
    )
    events = Queue()
    successful = False
    busy = False

    def request(dialog, **options):
        result = Queue()
        events.put(lambda: result.put(dialog(parent=root, **options)))
        return result.get()

    def choose_controller(serials):
        choices = "\n".join(f"{index}. {serial}" for index, serial in enumerate(serials, 1))
        return request(simpledialog.askinteger, title="Контроллер", prompt="Номер контроллера:\n" + choices,
                       minvalue=1, maxvalue=len(serials))

    def ask_pairing_code():
        return request(simpledialog.askstring, title="Код привязки",
                       prompt="Введите восьмизначный код, только что выданный домашним шлюзом.", show="*")

    def finish(error):
        nonlocal successful, busy
        busy = False
        if not error:
            successful = True
            root.destroy()
            return
        status.set(error)
        for widget in [*entries, paste, start]:
            widget.state(["!disabled"])
        password.focus_set()

    def submit():
        nonlocal busy
        if busy:
            return
        email, secret, otp, address = (item.get() for item in variables)
        if not email.strip() or not secret:
            status.set("Введите почту и пароль WB Cloud")
            return
        busy = True
        variables[1].set("")
        variables[2].set("")
        show_password.set(False)
        password.config(show="*")
        for widget in [*entries, paste, start]:
            widget.state(["disabled"])
        status.set("Проверяю доступ к свету через WB Cloud…")

        def worker():
            try:
                error = prepare(email, secret, otp, address.strip(), choose_controller, ask_pairing_code)
            except Exception:
                error = "Подготовка прервана. Повторите попытку"
            events.put(lambda: finish(error))

        Thread(target=worker, daemon=True).start()

    def poll():
        try:
            action = events.get_nowait()
        except Empty:
            pass
        else:
            action()
        if not successful:
            root.after(75, poll)

    start = ttk.Button(frame, text="Подготовить доступ", command=submit)
    start.grid(row=8, column=0, columnspan=3, sticky="e", pady=(6, 0))
    root.bind("<Return>", lambda _event: submit())
    root.protocol("WM_DELETE_WINDOW", lambda: None if busy else root.destroy())
    entries[0].focus_set()
    root.after(75, poll)
    root.mainloop()
    return successful
