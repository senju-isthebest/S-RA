import { contextBridge, ipcRenderer } from 'electron';

export const ALLOWED_IPC_CHANNELS = [
  'sora:loadState',
  'sora:saveState',
  'sora:openBookFile',
  'sora:askBook',
  'sora:evaluateExamAnswer',
  'sora:setSecret',
  'sora:getSecretStatus',
  'sora:exportJson',
  'sora:syncNow'
] as const;

contextBridge.exposeInMainWorld('soraDesktop', {
  loadState: () => ipcRenderer.invoke('sora:loadState'),
  saveState: (state: unknown) => ipcRenderer.invoke('sora:saveState', state),
  openBookFile: () => ipcRenderer.invoke('sora:openBookFile'),
  askBook: (bookId: string, query: string) => ipcRenderer.invoke('sora:askBook', bookId, query),
  evaluateExamAnswer: (question: unknown, answer: string) => ipcRenderer.invoke('sora:evaluateExamAnswer', question, answer),
  setSecret: (keyName: string, value: string) => ipcRenderer.invoke('sora:setSecret', keyName, value),
  getSecretStatus: (keyName: string) => ipcRenderer.invoke('sora:getSecretStatus', keyName),
  exportJson: () => ipcRenderer.invoke('sora:exportJson'),
  syncNow: () => ipcRenderer.invoke('sora:syncNow')
});
