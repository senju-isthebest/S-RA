import { contextBridge, ipcRenderer } from 'electron';

export const ALLOWED_IPC_CHANNELS = [
  'sora:loadState',
  'sora:saveState',
  'sora:openBookFile',
  'sora:deleteBook',
  'sora:askBook',
  'sora:analyzeChapters',
  'sora:testOllama',
  'sora:evaluateExamAnswer',
  'sora:setSecret',
  'sora:getSecretStatus',
  'sora:exportJson',
  'sora:authStatus',
  'sora:authSignIn',
  'sora:authSignUp',
  'sora:authSignOut',
  'sora:authDeleteAccount',
  'sora:retryDeadLetters',
  'sora:syncNow'
] as const;

contextBridge.exposeInMainWorld('soraDesktop', {
  loadState: () => ipcRenderer.invoke('sora:loadState'),
  saveState: (state: unknown) => ipcRenderer.invoke('sora:saveState', state),
  openBookFile: () => ipcRenderer.invoke('sora:openBookFile'),
  deleteBook: (bookId: string) => ipcRenderer.invoke('sora:deleteBook', bookId),
  askBook: (bookId: string, query: string) => ipcRenderer.invoke('sora:askBook', bookId, query),
  analyzeChapters: (bookId: string) => ipcRenderer.invoke('sora:analyzeChapters', bookId),
  testOllama: () => ipcRenderer.invoke('sora:testOllama'),
  evaluateExamAnswer: (question: unknown, answer: string) => ipcRenderer.invoke('sora:evaluateExamAnswer', question, answer),
  setSecret: (keyName: string, value: string) => ipcRenderer.invoke('sora:setSecret', keyName, value),
  getSecretStatus: (keyName: string) => ipcRenderer.invoke('sora:getSecretStatus', keyName),
  exportJson: () => ipcRenderer.invoke('sora:exportJson'),
  authStatus: () => ipcRenderer.invoke('sora:authStatus'),
  authSignIn: (email: string, password: string) => ipcRenderer.invoke('sora:authSignIn', email, password),
  authSignUp: (email: string, password: string, displayName: string) =>
    ipcRenderer.invoke('sora:authSignUp', email, password, displayName),
  authSignOut: () => ipcRenderer.invoke('sora:authSignOut'),
  authDeleteAccount: (confirmText: string) => ipcRenderer.invoke('sora:authDeleteAccount', confirmText),
  retryDeadLetters: () => ipcRenderer.invoke('sora:retryDeadLetters'),
  syncNow: () => ipcRenderer.invoke('sora:syncNow')
});
