(() => {
    document.querySelectorAll(".mail-swal").forEach(alert => Swal.fire({
        icon: alert.classList.contains("mail-swal-success") ? "success" : "info",
        title: alert.title,
        text: alert.textContent
    }));

    const post = (action, values, csrf) => {
        const form = document.createElement("form");
        form.method = "post";
        form.action = "mail";
        Object.entries({ action, csrf, ...values }).forEach(([name, value]) => (Array.isArray(value) ? value : [value]).forEach(item => {
            const input = document.createElement("input");
            input.type = "hidden";
            input.name = name;
            input.value = item ?? "";
            form.append(input);
        }));
        document.body.append(form);
        form.submit();
    };

    let draggedFolder = null;
    let draggedMessage = null;
    document.querySelectorAll(".mail-folder[draggable=true]").forEach(folder => {
        folder.addEventListener("dragstart", event => {
            draggedFolder = folder.dataset.folder;
            draggedMessage = null;
            event.dataTransfer.effectAllowed = "move";
            event.dataTransfer.setData("text/plain", draggedFolder);
            folder.classList.add("dragging");
        });
        folder.addEventListener("dragend", () => {
            draggedFolder = null;
            folder.classList.remove("dragging");
        });
    });

    document.querySelectorAll(".mail-message-row[data-message-uid]").forEach(message => {
        message.addEventListener("dragstart", event => {
            const checked = [...document.querySelectorAll(".mail-message-select:checked")].map(input => input.value);
            draggedMessage = { uid: checked.includes(message.dataset.messageUid) ? checked : [message.dataset.messageUid],
                source: message.dataset.folder };
            draggedFolder = null;
            event.dataTransfer.effectAllowed = "move";
            event.dataTransfer.setData("text/plain", draggedMessage.uid);
            message.classList.add("dragging");
        });
        message.addEventListener("dragend", () => {
            draggedMessage = null;
            message.classList.remove("dragging");
        });
    });

    document.querySelectorAll(".mail-folder[data-folder], [data-drop-root]").forEach(target => {
        target.addEventListener("dragover", event => {
            const destination = target.dataset.folder;
            if (draggedMessage ? !destination || destination === draggedMessage.source
                    : !draggedFolder || destination === draggedFolder) return;
            event.preventDefault();
            target.classList.add("drag-over");
        });
        target.addEventListener("dragleave", () => target.classList.remove("drag-over"));
        target.addEventListener("drop", event => {
            event.preventDefault();
            event.stopPropagation();
            target.classList.remove("drag-over");
            const csrf = document.querySelector("[data-csrf]")?.dataset.csrf;
            if (draggedMessage && csrf) {
                const message = draggedMessage;
                draggedMessage = null;
                post("messageMove", { ...message, target: target.dataset.folder }, csrf);
            } else if (draggedFolder && csrf) {
                const folder = draggedFolder;
                draggedFolder = null;
                post("folderMove", { folder, parent: target.dataset.folder || "" }, csrf);
            }
        });
    });

    const rename = document.querySelector("#mail-folder-rename");
    rename?.addEventListener("click", async () => {
        const { value: name } = await Swal.fire({
            title: "Renombrar carpeta",
            input: "text",
            inputLabel: "Nuevo nombre",
            showCancelButton: true,
            confirmButtonText: "Renombrar",
            cancelButtonText: "Cancelar"
        });
        if (name?.trim()) post("folderRename", { folder: rename.dataset.folder, name: name.trim() }, rename.dataset.csrf);
    });

    const remove = document.querySelector("#mail-folder-delete");
    remove?.addEventListener("click", async () => {
        const result = await Swal.fire({
            title: "¿Eliminar carpeta?",
            text: "Se eliminará la carpeta y todo su contenido.",
            icon: "warning",
            showCancelButton: true,
            confirmButtonText: "Eliminar",
            cancelButtonText: "Cancelar",
            confirmButtonColor: "#b52f3a"
        });
        if (result.isConfirmed)
            post("folderDelete", { folder: remove.dataset.folder }, remove.dataset.csrf);
    });

    const folderMenu = document.querySelector("#mail-folder-menu");
    const closeFolderMenu = () => folderMenu?.classList.remove("open");
    document.querySelectorAll(".mail-folder[data-folder]").forEach(folder => {
        folder.addEventListener("contextmenu", event => {
            if (folder.dataset.folder.toUpperCase() === "INBOX") return;
            event.preventDefault();
            rename.dataset.folder = remove.dataset.folder = folder.dataset.folder;
            rename.dataset.csrf = remove.dataset.csrf = folderMenu.dataset.csrf;
            folderMenu.classList.add("open");
            folderMenu.style.left = `${Math.min(event.clientX, window.innerWidth - 200)}px`;
            folderMenu.style.top = `${Math.min(event.clientY, window.innerHeight - 100)}px`;
        });
    });
    document.addEventListener("click", closeFolderMenu);
    document.addEventListener("keydown", event => {
        if (event.key === "Escape") closeFolderMenu();
    });
    window.addEventListener("scroll", closeFolderMenu, true);

    const bulkForm = document.querySelector("#mail-bulk-form");
    const selectAll = document.querySelector("#mail-select-all");
    const deleteSelected = document.querySelector("#mail-delete-selected");
    const selections = [...document.querySelectorAll(".mail-message-select")];
    const updateSelection = () => {
        const count = selections.filter(input => input.checked).length;
        if (selectAll) {
            selectAll.checked = count === selections.length;
            selectAll.indeterminate = count > 0 && count < selections.length;
        }
        if (deleteSelected) deleteSelected.disabled = count === 0;
    };
    selectAll?.addEventListener("change", () => {
        selections.forEach(input => input.checked = selectAll.checked);
        updateSelection();
    });
    selections.forEach(input => input.addEventListener("change", updateSelection));
    bulkForm?.addEventListener("submit", async event => {
        event.preventDefault();
        const count = selections.filter(input => input.checked).length;
        if (!count) return;
        const result = await Swal.fire({
            title: `¿Eliminar ${count} mensaje${count === 1 ? "" : "s"}?`,
            text: "En Papelera la eliminación será definitiva.",
            icon: "warning",
            showCancelButton: true,
            confirmButtonText: "Eliminar",
            cancelButtonText: "Cancelar",
            confirmButtonColor: "#b52f3a"
        });
        if (result.isConfirmed) bulkForm.submit();
    });

    const contactPicker = document.querySelector("#mail-contact-picker");
    const contactSearch = document.querySelector("#mail-contact-search");
    let contactTarget = null;
    const closeContacts = () => {
        contactPicker?.classList.remove("open");
        document.querySelectorAll(".mail-contact-open").forEach(button => button.setAttribute("aria-expanded", "false"));
    };
    document.querySelectorAll(".mail-contact-open").forEach(button => button.addEventListener("click", () => {
        contactTarget = document.querySelector(`#${button.dataset.contactTarget}`);
        document.querySelectorAll(".mail-contact-open").forEach(item => item.setAttribute("aria-expanded", "false"));
        button.setAttribute("aria-expanded", "true");
        contactPicker.classList.add("open");
        contactSearch.focus();
    }));
    document.querySelector("#mail-contact-close")?.addEventListener("click", closeContacts);
    document.querySelectorAll(".mail-contact-item").forEach(contact => contact.addEventListener("click", () => {
        if (!contactTarget) return;
        const values = contactTarget.value.split(",").map(value => value.trim()).filter(Boolean);
        if (!values.some(value => value.toLowerCase() === contact.dataset.contactEmail.toLowerCase()))
            values.push(contact.dataset.contactEmail);
        contactTarget.value = values.join(", ");
        contactTarget.focus();
    }));
    contactSearch?.addEventListener("input", () => {
        const query = contactSearch.value.toLowerCase().trim();
        document.querySelectorAll(".mail-contact-item").forEach(contact => {
            contact.hidden = query && !contact.textContent.toLowerCase().includes(query);
        });
    });
    document.addEventListener("keydown", event => {
        if (event.key === "Escape") closeContacts();
    });

    const editor = document.querySelector("#mail-compose-body");
    const markdown = {
        bold: ["**", "**"],
        italic: ["_", "_"],
        heading: ["## ", ""],
        list: ["- ", ""],
        link: ["[", "](https://)"]
    };
    document.querySelectorAll("[data-md]").forEach(button => button.addEventListener("click", () => {
        if (!editor) return;
        const [before, after] = markdown[button.dataset.md];
        const start = editor.selectionStart;
        const end = editor.selectionEnd;
        const selected = editor.value.slice(start, end) || (button.dataset.md === "link" ? "texto" : "");
        editor.setRangeText(before + selected + after, start, end, "end");
        editor.focus();
    }));
})();
