# Usage

Prepare the server first, as passalong's README describes under
[server setup](https://github.com/joelee/passalong#server-setup): an SSH
server, a `passalong` user, and a storage directory.

## Setup

The app opens on the setup screen until the device has a config.

1. **This device's public key.** The app generates an ed25519 key the
   first time. Copy the line shown and add it to
   `~passalong/.ssh/authorized_keys` on the server. *Replace* makes a new
   key; the server then needs the new line.
2. **The server.** Host name or address, SSH port (22), the user
   (`passalong`), the storage directory (`/srv/passalong`), and a name for
   this device, recorded on every item it sends.
3. **Connect.** The app fetches the server's host key and shows its
   fingerprint:

   > The server at nas.local:22 presented this ssh-ed25519 host key:
   > `SHA256:5Si4lWKPwa0+I2wCQf3eOtcF8jWo30BWybHoXLTxABo`
   > Compare it with the server's own key, for example by running there:
   > `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`

   Compare it with the server's own key. *Yes, pin it* writes the config
   with that key pinned; *No* writes nothing. There is no
   trust-on-first-use: any other key is refused from then on.

The choose screen then connects, which proves the settings. A failure
shows the core's message, such as `cannot connect to nas.local:22` or the
login being refused because the public key is not installed yet, with
*Try again* and *Set up again*.

## Choose

The list of stored items, newest first, as `passalong choose` shows it.
Each row has the item's content key, its name (files) or the start of its
text, and its kind, size, sending device, and creation time in local time.
The bottom line shows the status, the store, and how many items there are.

| Touch | Action | TUI key |
|---|---|---|
| Tap a row | Load the item: text goes to the clipboard; files and clipboard images are saved to Downloads, checked against their SHA-256 first, under a numbered name if theirs is taken | Enter |
| Long press a row | *Load*, *Show content*, *Details*, or *Delete* | `c`, `g`, `d` |
| Search icon | Filter: type to match the id, name or preview, device, or kind, ignoring case; Done keeps the filter, the clear button removes it | `/`, Enter, Esc |
| Refresh icon | Show the list again: from the list cache when it is fresh, otherwise from the server | `r` |
| Menu, *Read from the server* | Read every item from the server and rewrite the cache | `R` |
| Menu, *Help* | The passalong version, what it is, and these actions | `?` |
| Menu, *Set up again* | Back to the setup screen | |
| Menu, *Quit* | Close the app | `q` |
| Back | Close what is open: the filter, a dialog, the content view; from the list, quit | Esc |

*Show content* opens a text item, or a file of a `text/` type, in a
full-screen view; other files are refused with `item <ID> is binary
(<mime>); load it instead`, as `cat` refuses them on a terminal.
*Details* shows the metadata as `passalong get` prints it. *Delete* asks
first, then reads the list again from the store, so items other devices
removed meanwhile disappear too.

The list opens from the list cache when it was checked within two
minutes, and the bottom line says how old it is, such as `cached 30 s
ago`. The connection is still made first, for the actions. While the list
is being read or an item deleted, the bottom line says `Loading...`,
`Reloading...`, or `Deleting <ID>...`, and touches wait until it is done.
A failure stays in the bottom line, such as `cannot delete <ID>: ...`, and
the list stays open.

## Encrypted stores

A store encrypted with `passalong encrypt` cannot be opened by this
version: the choose screen shows the core's message asking to join with
`passalong encrypt --join`. Joining from the app is on the
[backlog](backlog.md).
