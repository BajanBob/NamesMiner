const { contextBridge, ipcRenderer } = require("electron");
contextBridge.exposeInMainWorld("nm", {
  open: (url) => ipcRenderer.send("open-external", url)
});
