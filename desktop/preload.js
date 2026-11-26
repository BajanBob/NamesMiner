const { contextBridge, ipcRenderer } = require("electron");
contextBridge.exposeInMainWorld("nm", {
  open: (url) => ipcRenderer.send("open-external", url),
  // files: [{ name, base64 }] -> asks for a folder once, writes them, opens the folder
  saveFiles: (files) => ipcRenderer.invoke("save-files", files)
});
