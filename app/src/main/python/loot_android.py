"""Python bridge between the Android UI and libloot.

Kotlin passes real filesystem paths, plugin name lists and a cached masterlist
path. This module returns a JSON string so the result shape is explicit and can
be written straight into the in-app log.
"""

import json
import logging
import os
import traceback

import loot

GAME_TYPES = {
    "skyrim_le": loot.GameType.Skyrim,
}

_MAX_LOGS = 300
_MAX_MESSAGES = 500


class _LogCollector(logging.Handler):
    def __init__(self):
        super().__init__(level=logging.WARNING)
        self.lines = []
        self.setFormatter(logging.Formatter("%(levelname)s %(name)s: %(message)s"))

    def emit(self, record):
        try:
            if len(self.lines) < _MAX_LOGS:
                self.lines.append(self.format(record))
        except Exception:
            pass


def _install_log_handler():
    root = logging.getLogger()
    for handler in root.handlers:
        if isinstance(handler, _LogCollector):
            return handler

    handler = _LogCollector()
    root.addHandler(handler)
    if root.level == logging.NOTSET or root.level > logging.WARNING:
        root.setLevel(logging.WARNING)
    return handler


_LOG_COLLECTOR = _install_log_handler()


def _normalize_plugin_names(names):
    seen = set()
    result = []
    for raw in names or []:
        name = str(raw).strip()
        if name.startswith("*"):
            name = name[1:].strip()
        if not name:
            continue
        key = name.lower()
        if key in seen:
            continue
        seen.add(key)
        result.append(name)
    return result


def _message_text(message):
    try:
        contents = list(message.content)
        if not contents:
            return str(message)
        selected = loot.select_message_content(contents, "en")
        if selected is not None:
            return selected.text
        return contents[0].text
    except Exception:
        return str(message)


def _message_type(message):
    try:
        return str(message.message_type).split(".")[-1].lower()
    except Exception:
        return "say"


def _collect_metadata_messages(database, plugin_names):
    messages = []
    seen = set()

    def add(plugin, message):
        text = _message_text(message).strip()
        if not text:
            return
        kind = _message_type(message)
        key = (plugin, kind, text)
        if key in seen:
            return
        seen.add(key)
        messages.append({"plugin": plugin, "type": kind, "text": text})

    for name in plugin_names:
        if len(messages) >= _MAX_MESSAGES:
            break
        try:
            metadata = database.plugin_metadata(name, True, True)
            for message in metadata.messages:
                add(name, message)
                if len(messages) >= _MAX_MESSAGES:
                    break
        except Exception:
            # A plugin without metadata is normal.
            pass

    try:
        for message in database.general_messages(True, True):
            add("", message)
            if len(messages) >= _MAX_MESSAGES:
                break
    except Exception:
        pass

    return messages


def probe():
    """Small diagnostic helper used by adb/logcat during development."""
    return {
        "liblootVersion": loot.libloot_version(),
        "liblootRevision": loot.libloot_revision(),
    }


def _split_plugin_list(value):
    """Split a newline-separated plugin list (as passed from Kotlin) into names."""
    if value is None:
        return []
    return [line for line in str(value).splitlines() if line.strip()]


def sort_load_order_json(
    game_id,
    game_root,
    data_dir,
    local_path,
    all_plugins,
    active_plugins,
    masterlist_path,
    userlist_path="",
):
    _LOG_COLLECTOR.lines.clear()

    try:
        game_type = GAME_TYPES.get(str(game_id).strip().lower())
        if game_type is None:
            raise ValueError("Unsupported game: %s" % game_id)

        game_root = os.path.abspath(str(game_root))
        data_dir = os.path.abspath(str(data_dir))
        local_path = os.path.abspath(str(local_path))
        masterlist_path = os.path.abspath(str(masterlist_path))

        if not os.path.isdir(game_root):
            raise ValueError("Game folder does not exist: %s" % game_root)
        if not os.path.isdir(data_dir):
            raise ValueError("Data folder does not exist: %s" % data_dir)
        if not os.path.isfile(masterlist_path):
            raise ValueError("LOOT masterlist does not exist: %s" % masterlist_path)

        os.makedirs(local_path, exist_ok=True)

        all_plugins = _normalize_plugin_names(_split_plugin_list(all_plugins))
        active_plugins = _normalize_plugin_names(_split_plugin_list(active_plugins))

        game = loot.Game(game_type, game_root, local_path)
        database = game.database()
        database.load_masterlist(masterlist_path)

        userlist_path = str(userlist_path or "").strip()
        if userlist_path:
            userlist_path = os.path.abspath(userlist_path)
            if os.path.isfile(userlist_path):
                database.load_userlist(userlist_path)

        plugin_paths = [os.path.join(data_dir, name) for name in all_plugins]
        game.load_plugin_headers(plugin_paths)

        loaded = {}
        for plugin in game.loaded_plugins():
            loaded[plugin.name().lower()] = plugin.name()

        sortable = [loaded[name.lower()] for name in all_plugins if name.lower() in loaded]
        missing_headers = [name for name in all_plugins if name.lower() not in loaded]

        if sortable:
            sorted_all = list(game.sort_plugins(sortable))
        else:
            sorted_all = []

        # Plugins libloot could not read are not silently dropped: keep them at
        # the end of loadorder.txt so the user can see and fix them.
        present = {name.lower() for name in sorted_all}
        for name in missing_headers:
            if name.lower() not in present:
                sorted_all.append(name)
                present.add(name.lower())

        active_keys = {name.lower() for name in active_plugins}
        sorted_active = [name for name in sorted_all if name.lower() in active_keys]

        # Keep active entries that were not in the discovered plugin set.
        for name in active_plugins:
            if name.lower() not in present:
                sorted_active.append(name)
                present.add(name.lower())

        messages = _collect_metadata_messages(database, sorted_all)
        general_messages = list(_LOG_COLLECTOR.lines)

        return json.dumps(
            {
                "ok": True,
                "liblootVersion": loot.libloot_version(),
                "loadedCount": len(sortable),
                "missingHeaders": missing_headers,
                "sortedAll": sorted_all,
                "sortedActive": sorted_active,
                "messages": messages,
                "generalMessages": general_messages,
            }
        )
    except Exception as failure:
        return json.dumps(
            {
                "ok": False,
                "error": str(failure),
                "traceback": traceback.format_exc(),
                "generalMessages": list(_LOG_COLLECTOR.lines),
            }
        )
