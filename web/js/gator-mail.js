(() => {
    const mobileChallenge = document.getElementById("mail-mobile-challenge");
    if (mobileChallenge) window.setTimeout(() => mobileChallenge.requestSubmit(), 2000);

    document.querySelectorAll(".mail-swal").forEach(alert => Swal.fire({
        icon: alert.classList.contains("mail-swal-success") ? "success" : "info",
        title: alert.title,
        text: alert.textContent
    }));

    document.querySelectorAll(".mail-password-reset").forEach(button => button.addEventListener("click", async event => {
        event.preventDefault();
        const result = await Swal.fire({
            title: "¿Restablecer contraseña?",
            text: "Se generará una contraseña temporal y el usuario deberá cambiarla al ingresar.",
            icon: "warning",
            showCancelButton: true,
            confirmButtonText: "Restablecer",
            cancelButtonText: "Cancelar"
        });
        if (result.isConfirmed) button.form.requestSubmit(button);
    }));

    document.querySelectorAll(".mail-user-delete").forEach(button => button.addEventListener("click", async event => {
        event.preventDefault();
        if (!button.form.reportValidity()) return;
        const result = await Swal.fire({
            title: `¿Eliminar a ${button.dataset.user}?`,
            text: "Primero se copiará todo su buzón a la cuenta genérica seleccionada. Después se eliminarán su acceso y cuenta Linux.",
            icon: "warning",
            showCancelButton: true,
            confirmButtonColor: "#b52f3a",
            confirmButtonText: "Trasladar y eliminar",
            cancelButtonText: "Cancelar"
        });
        if (result.isConfirmed) button.form.requestSubmit(button);
    }));

    document.querySelectorAll(".mail-filter-rule-form").forEach(form => {
        const field = form.elements.field;
        const operator = form.elements.operator;
        const header = form.elements.header;
        const headerLabel = form.querySelector(".mail-filter-header");
        const value = form.querySelector(".mail-filter-value");
        const update = () => {
            const size = field.value === "SIZE";
            const customHeader = field.value === "HEADER";
            const allowed = size ? ["GT", "LT"] : ["CONTAINS", "EQUALS", "STARTS_WITH", "ENDS_WITH"];
            [...operator.options].forEach(option => {
                option.hidden = option.disabled = !allowed.includes(option.value);
            });
            if (!allowed.includes(operator.value)) operator.value = allowed[0];
            headerLabel.hidden = !customHeader;
            header.required = customHeader;
            if (!customHeader) header.value = "";
            value.inputMode = size ? "numeric" : "text";
            if (size) value.setAttribute("pattern", "[0-9]{1,12}");
            else value.removeAttribute("pattern");
            value.placeholder = size ? "1048576" : "Texto, dirección o asunto";
        };
        field.addEventListener("change", update);
        update();
        form.addEventListener("submit", async event => {
            if (event.submitter?.value !== "filterSave" || !form.elements.applyExisting?.checked
                    || form.dataset.replayConfirmed) return;
            event.preventDefault();
            const result = await Swal.fire({
                title: "¿Revisar mensajes existentes?",
                text: "Se volverán a evaluar los mensajes que permanecen en Entrada y podrían moverse de carpeta.",
                icon: "question",
                showCancelButton: true,
                confirmButtonText: "Guardar y revisar",
                cancelButtonText: "Cancelar"
            });
            if (result.isConfirmed) {
                form.dataset.replayConfirmed = "true";
                form.requestSubmit(event.submitter);
            }
        });
    });

    document.querySelectorAll(".mail-filter-delete").forEach(button => button.addEventListener("click", async event => {
        event.preventDefault();
        const result = await Swal.fire({
            title: "¿Eliminar este filtro?",
            text: "Los mensajes que ya fueron movidos no cambiarán.",
            icon: "warning",
            showCancelButton: true,
            confirmButtonText: "Eliminar",
            cancelButtonText: "Cancelar",
            confirmButtonColor: "#b52f3a"
        });
        if (result.isConfirmed) button.form.requestSubmit(button);
    }));

    document.querySelector(".mail-filter-apply")?.addEventListener("click", async event => {
        event.preventDefault();
        const button = event.currentTarget;
        const result = await Swal.fire({
            title: "¿Aplicar filtros a Entrada?",
            text: "Se revisarán los mensajes existentes y podrían moverse según el orden de las reglas activas.",
            icon: "question",
            showCancelButton: true,
            confirmButtonText: "Aplicar filtros",
            cancelButtonText: "Cancelar"
        });
        if (result.isConfirmed) button.form.requestSubmit(button);
    });

    document.querySelectorAll(".mail-event-complete-form").forEach(form => form.addEventListener("submit", async event => {
        event.preventDefault();
        const result = await Swal.fire({
            title: "¿Concluir evento?",
            text: "El evento dejará de aparecer como pendiente.",
            icon: "question",
            showCancelButton: true,
            confirmButtonText: "Concluir",
            cancelButtonText: "Cancelar"
        });
        if (result.isConfirmed) form.submit();
    }));

    const eventRows = [...document.querySelectorAll("#mail-dashboard-events .mail-dashboard-event")];
    const eventPageSize = document.getElementById("mail-events-page-size");
    const eventPageStatus = document.getElementById("mail-events-page-status");
    const eventPrevious = document.getElementById("mail-events-prev");
    const eventNext = document.getElementById("mail-events-next");
    if (eventPageSize && eventPageStatus && eventPrevious && eventNext) {
        let page = 0;
        const renderEvents = () => {
            const size = Number(eventPageSize.value) || 5;
            const pages = Math.max(1, Math.ceil(eventRows.length / size));
            page = Math.max(0, Math.min(page, pages - 1));
            eventRows.forEach((row, index) => row.hidden = index < page * size || index >= (page + 1) * size);
            eventPageStatus.textContent = `${eventRows.length} eventos · ${page + 1} / ${pages}`;
            eventPrevious.disabled = page === 0;
            eventNext.disabled = page >= pages - 1;
        };
        eventPageSize.addEventListener("change", () => { page = 0; renderEvents(); });
        eventPrevious.addEventListener("click", () => { page--; renderEvents(); });
        eventNext.addEventListener("click", () => { page++; renderEvents(); });
        renderEvents();
    }

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

    const child = document.querySelector("#mail-folder-child");
    child?.addEventListener("click", async () => {
        const { value: name } = await Swal.fire({
            title: "Nueva subcarpeta",
            input: "text",
            inputLabel: `Dentro de ${child.dataset.folder}`,
            showCancelButton: true,
            confirmButtonText: "Crear",
            cancelButtonText: "Cancelar"
        });
        if (name?.trim()) post("folderCreate", { parent: child.dataset.folder, name: name.trim() }, child.dataset.csrf);
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

    const emptyTrash = document.querySelector("#mail-folder-empty-trash");
    emptyTrash?.addEventListener("click", async () => {
        const result = await Swal.fire({
            title: "¿Vaciar la Papelera?",
            text: "Todos los mensajes se eliminarán definitivamente.",
            icon: "warning",
            showCancelButton: true,
            confirmButtonText: "Vaciar Papelera",
            cancelButtonText: "Cancelar",
            confirmButtonColor: "#b52f3a"
        });
        if (result.isConfirmed)
            post("folderEmptyTrash", { folder: emptyTrash.dataset.folder }, emptyTrash.dataset.csrf);
    });

    const folderMenu = document.querySelector("#mail-folder-menu");
    const closeFolderMenu = () => folderMenu?.classList.remove("open");
    document.querySelectorAll(".mail-folder[data-folder]").forEach(folder => {
        folder.addEventListener("contextmenu", event => {
            if (folder.dataset.folder.toUpperCase() === "INBOX") return;
            if (folder.classList.contains("mail-folder-system") && !folder.classList.contains("mail-folder-trash")) return;
            event.preventDefault();
            child.dataset.folder = rename.dataset.folder = remove.dataset.folder = folder.dataset.folder;
            child.dataset.csrf = rename.dataset.csrf = remove.dataset.csrf = folderMenu.dataset.csrf;
            const trash = folder.classList.contains("mail-folder-trash");
            const system = folder.classList.contains("mail-folder-system");
            child.hidden = rename.hidden = remove.hidden = system;
            emptyTrash.hidden = !trash;
            emptyTrash.dataset.folder = folder.dataset.folder;
            emptyTrash.dataset.csrf = folderMenu.dataset.csrf;
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
    document.querySelector(".mail-message-delete-form")?.addEventListener("submit", async event => {
        event.preventDefault();
        const form = event.currentTarget;
        const result = await Swal.fire({
            title: "¿Eliminar este mensaje?",
            text: "En Papelera la eliminación será definitiva.",
            icon: "warning",
            showCancelButton: true,
            confirmButtonText: "Eliminar",
            cancelButtonText: "Cancelar",
            confirmButtonColor: "#b52f3a"
        });
        if (result.isConfirmed) form.submit();
    });

    const contactPicker = document.querySelector("#mail-contact-picker");
    const contactSearch = document.querySelector("#mail-contact-search");
    let contactTarget = null;
    let replaceContactToken = false;
    const filterContacts = query => {
        let matches = 0;
        document.querySelectorAll(".mail-contact-item").forEach(contact => {
            contact.hidden = query && !contact.textContent.toLowerCase().includes(query);
            if (!contact.hidden) matches++;
        });
        return matches;
    };
    const closeContacts = () => {
        contactPicker?.classList.remove("open");
        document.querySelectorAll(".mail-contact-open").forEach(button => button.setAttribute("aria-expanded", "false"));
    };
    document.querySelectorAll(".mail-contact-open").forEach(button => button.addEventListener("click", () => {
        contactTarget = document.querySelector(`#${button.dataset.contactTarget}`);
        replaceContactToken = false;
        filterContacts("");
        document.querySelectorAll(".mail-contact-open").forEach(item => item.setAttribute("aria-expanded", "false"));
        button.setAttribute("aria-expanded", "true");
        contactPicker.classList.add("open");
        contactSearch.focus();
    }));
    document.querySelector("#mail-contact-close")?.addEventListener("click", closeContacts);
    document.querySelectorAll(".mail-contact-item").forEach(contact => contact.addEventListener("click", () => {
        if (!contactTarget) return;
        const values = contactTarget.value.split(",").map(value => value.trim()).filter(Boolean);
        if (replaceContactToken && values.length) values.pop();
        if (!values.some(value => value.toLowerCase() === contact.dataset.contactEmail.toLowerCase()))
            values.push(contact.dataset.contactEmail);
        contactTarget.value = values.join(", ");
        replaceContactToken = false;
        closeContacts();
        contactTarget.focus();
    }));
    contactSearch?.addEventListener("input", () => {
        const query = contactSearch.value.toLowerCase().trim();
        filterContacts(query);
    });
    document.querySelectorAll(".mail-recipient-input").forEach(input => input.addEventListener("input", () => {
        const query = input.value.split(",").pop().trim().toLowerCase();
        contactTarget = input;
        replaceContactToken = Boolean(query);
        contactSearch.value = query;
        const matches = query ? filterContacts(query) : 0;
        contactPicker?.classList.toggle("open", matches > 0);
    }));
    document.addEventListener("keydown", event => {
        if (event.key === "Escape") closeContacts();
    });

    const editor = document.querySelector("#mail-compose-body");
    const visualEditor = document.querySelector("#mail-html-editor");
    const format = document.querySelector("#mail-compose-format");
    const markdownFormat = document.querySelector("#mail-format-markdown");
    const htmlFormat = document.querySelector("#mail-format-html");
    const syncHtml = () => {
        if (!editor || !visualEditor) return;
        const content = visualEditor.cloneNode(true);
        content.querySelectorAll("img[data-cid]").forEach(image => {
            image.src = "cid:" + image.dataset.cid;
            image.removeAttribute("data-cid");
        });
        editor.value = content.innerHTML;
    };
    if (editor && visualEditor) visualEditor.innerHTML = editor.value;
    const setFormat = () => {
        if (!editor || !visualEditor || !format) return;
        const html = format.value === "html";
        markdownFormat?.classList.toggle("btn-primary", !html);
        markdownFormat?.classList.toggle("btn-light", html);
        htmlFormat?.classList.toggle("btn-primary", html);
        htmlFormat?.classList.toggle("btn-light", !html);
        visualEditor.classList.toggle("d-none", !html);
        editor.classList.toggle("d-none", html);
        editor.setAttribute("aria-label", "Contenido Markdown");
    };
    markdownFormat?.addEventListener("click", () => { syncHtml(); format.value = "markdown"; setFormat(); });
    htmlFormat?.addEventListener("click", () => {
        if (format.value === "markdown") visualEditor.textContent = editor.value;
        format.value = "html";
        setFormat();
    });
    visualEditor?.addEventListener("input", syncHtml);
    visualEditor?.closest("form")?.addEventListener("submit", syncHtml);
    setFormat();
    const markdown = {
        bold: ["**", "**"], italic: ["_", "_"], heading: ["## ", ""], list: ["- ", ""],
        link: ["[", "](https://)"],
        table: ["<table><thead><tr><th>Columna 1</th><th>Columna 2</th></tr></thead><tbody><tr><td>Dato 1</td><td>Dato 2</td></tr></tbody></table>", ""]
    };
    document.querySelectorAll("[data-md]").forEach(button => button.addEventListener("click", () => {
        if (!editor || !format) return;
        if (format.value === "markdown") {
            const [before, after] = markdown[button.dataset.md];
            const start = editor.selectionStart;
            const end = editor.selectionEnd;
            const selected = editor.value.slice(start, end) || (button.dataset.md === "link" ? "texto" : "");
            editor.setRangeText(before + selected + after, start, end, "end");
            editor.focus();
            return;
        }
        visualEditor?.focus();
        const command = { bold: "bold", italic: "italic", heading: "formatBlock", list: "insertUnorderedList" }[button.dataset.md];
        if (command) document.execCommand(command, false, button.dataset.md === "heading" ? "h2" : null);
        else if (button.dataset.md === "table") document.execCommand("insertHTML", false,
            "<table><thead><tr><th>Columna 1</th><th>Columna 2</th></tr></thead><tbody><tr><td>Dato 1</td><td>Dato 2</td></tr></tbody></table><p><br></p>");
        else {
            const url = prompt("Dirección del enlace (https:// o mailto:)", "https://");
            if (url && /^(https?:|mailto:)/i.test(url)) document.execCommand("createLink", false, url);
        }
        syncHtml();
    }));
    const images = document.querySelector("#mail-compose-images");
    document.querySelector("#mail-insert-image")?.addEventListener("click", () => images?.click());
    images?.addEventListener("change", () => {
        if (!visualEditor) return;
        visualEditor.querySelectorAll("img[data-cid]").forEach(image => image.remove());
        Array.from(images.files).forEach((file, index) => {
            const image = document.createElement("img");
            image.src = URL.createObjectURL(file);
            image.alt = file.name;
            image.dataset.cid = `inline-${index + 1}@gator-mail`;
            visualEditor.append(image);
        });
        syncHtml();
    });
})();
