interface SoraDesktopBridge {
  loadState(): Promise<DesktopRendererState>;
  saveState(state: DesktopRendererState): Promise<boolean>;
  openBookFile(): Promise<DesktopBook | null>;
  askBook(bookId: string, query: string): Promise<{ passages: Array<{ chapterNumber: number; pageNumber: number; excerpt: string }>; answer: string }>;
  evaluateExamAnswer(question: DesktopQuestion, answer: string): Promise<{ isCorrect: boolean; feedback: string }>;
  setSecret(keyName: string, value: string): Promise<boolean>;
  getSecretStatus(keyName: string): Promise<boolean>;
  exportJson(): Promise<string | null>;
  syncNow(): Promise<{ status: string; pendingCount: number }>;
}

interface DesktopBook {
  id: string;
  title: string;
  subjectName: string;
  totalPages: number;
  currentPage: number;
  bookmarkedPages: number[];
  pages: Array<{ pageNumber: number; text: string }>;
}

interface DesktopQuestion {
  id: string;
  bookId: string;
  chapterNumber: number;
  questionType: string;
  questionText: string;
  correctAnswer: string;
  marks: number;
}

interface DesktopRendererState {
  language: 'en' | 'ar';
  localOnlyPrivacyMode: boolean;
  aiConfig: {
    strategy: string;
    ollamaEndpoint: string;
    ollamaModel: string;
    geminiModel: string;
    fallbackEnabled: boolean;
    timeoutMs: number;
  };
  books: DesktopBook[];
  questions: DesktopQuestion[];
  notes: Array<{ id: string; title: string; contentMarkdown: string }>;
}

interface Window {
  soraDesktop?: SoraDesktopBridge;
}

let activeTab = 'library';
let state: DesktopRendererState = {
  language: 'en',
  localOnlyPrivacyMode: false,
  aiConfig: {
    strategy: 'AUTO',
    ollamaEndpoint: 'http://127.0.0.1:11434',
    ollamaModel: 'llama3.2:3b',
    geminiModel: 'gemini-3-flash-preview',
    fallbackEnabled: true,
    timeoutMs: 30000
  },
  books: [],
  questions: [],
  notes: []
};
let selectedBookId = '';
let qaOutput = '';
let syncOutput = 'IDLE';

function applyDir(): void {
  document.documentElement.lang = state.language;
  document.documentElement.dir = state.language === 'ar' ? 'rtl' : 'ltr';
}

function el<K extends keyof HTMLElementTagNameMap>(tag: K, text = '', className = ''): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  if (text) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function render(): void {
  applyDir();
  const root = document.getElementById('app');
  if (!root) return;
  root.replaceChildren();

  const shell = el('div', '', 'shell');
  const isAr = state.language === 'ar';

  const header = el('div', '', 'header');
  const brand = el('div');
  brand.append(
    el('strong', isAr ? 'سُورا لسطح المكتب' : 'SŌRA Desktop'),
    el('div', isAr ? 'بيئة التعلم الذكية لنظام ويندوز' : 'Windows Learning Workspace & Offline AI Engine', 'muted')
  );

  const controls = el('div', '', 'tabs');
  const enBtn = el('button', 'EN', state.language === 'en' ? 'active' : '');
  enBtn.onclick = async () => {
    state.language = 'en';
    await window.soraDesktop?.saveState(state);
    render();
  };
  const arBtn = el('button', 'عربي', state.language === 'ar' ? 'active' : '');
  arBtn.onclick = async () => {
    state.language = 'ar';
    await window.soraDesktop?.saveState(state);
    render();
  };
  controls.append(enBtn, arBtn);
  header.append(brand, controls);

  const nav = el('div', '', 'tabs');
  const navItems: Array<[string, string]> = [
    ['library', isAr ? 'المكتبة' : 'Library'],
    ['reader', isAr ? 'القارئ والأسئلة' : 'Reader & Q&A'],
    ['exams', isAr ? 'الاختبارات' : 'Exam Engine'],
    ['security', isAr ? 'المزامنة والأمان' : 'Sync & Security']
  ];
  navItems.forEach(([id, label]) => {
    const b = el('button', label, activeTab === id ? 'active' : '');
    b.onclick = () => {
      activeTab = id;
      render();
    };
    nav.append(b);
  });

  const body = el('div', '', 'card');
  const currentBook = state.books.find((b) => b.id === selectedBookId) ?? state.books[0];

  if (activeTab === 'library') {
    const openBtn = el('button', isAr ? 'فتح ملف كتاب (.txt / .md / .pdf)' : 'Open Textbook File (.txt / .md / .pdf)', 'primary');
    openBtn.onclick = async () => {
      const imported = await window.soraDesktop?.openBookFile();
      if (imported) {
        state = (await window.soraDesktop?.loadState()) ?? state;
        selectedBookId = imported.id;
        activeTab = 'reader';
        render();
      }
    };
    body.append(openBtn);
    state.books.forEach((b) => {
      const row = el('div', '', 'passage');
      const btn = el('button', `${b.title} (${b.totalPages}p)`);
      btn.onclick = () => {
        selectedBookId = b.id;
        activeTab = 'reader';
        render();
      };
      row.append(btn);
      body.append(row);
    });
  } else if (activeTab === 'reader') {
    if (!currentBook) {
      body.append(el('p', isAr ? 'قم باستيراد كتاب من المكتبة أولاً.' : 'Import a textbook in Library first.', 'muted'));
    } else {
      body.append(el('h3', `${currentBook.title} — p.${currentBook.currentPage}/${currentBook.totalPages}`));
      const pageObj = currentBook.pages[currentBook.currentPage - 1];
      body.append(el('p', pageObj?.text ?? ''));

      const qInput = el('textarea') as HTMLTextAreaElement;
      qInput.rows = 3;
      qInput.placeholder = isAr ? 'اسأل عبر كامل الكتاب...' : 'Ask across the whole textbook...';
      const askBtn = el('button', isAr ? 'اسأل الكتاب' : 'Ask Whole Book', 'primary');
      askBtn.onclick = async () => {
        const res = await window.soraDesktop?.askBook(currentBook.id, qInput.value);
        if (res) {
          qaOutput = res.answer;
          render();
        }
      };
      body.append(qInput, askBtn);
      if (qaOutput) body.append(el('div', qaOutput, 'passage'));
    }
  } else if (activeTab === 'exams') {
    const items = state.questions.filter((q) => !currentBook || q.bookId === currentBook.id);
    if (items.length === 0) {
      body.append(el('p', isAr ? 'لا توجد أسئلة بعد.' : 'No generated questions yet.', 'muted'));
    } else {
      items.slice(0, 8).forEach((q) => {
        const card = el('div', '', 'passage');
        card.append(el('strong', q.questionText));
        const ans = el('input') as HTMLInputElement;
        const fb = el('div', '', 'muted');
        const chk = el('button', isAr ? 'تحقق' : 'Check Answer');
        chk.onclick = async () => {
          const ev = await window.soraDesktop?.evaluateExamAnswer(q, ans.value);
          if (ev) fb.textContent = `${ev.isCorrect ? '✓' : '✗'} ${ev.feedback}`;
        };
        card.append(ans, chk, fb);
        body.append(card);
      });
    }
  } else {
    const syncBtn = el('button', isAr ? 'مزامنة الآن' : 'Sync Now', 'primary');
    syncBtn.onclick = async () => {
      const s = await window.soraDesktop?.syncNow();
      if (s) {
        syncOutput = `${s.status} (${s.pendingCount})`;
        render();
      }
    };
    const expBtn = el('button', isAr ? 'تصدير بيانات الدراسة JSON' : 'Export Workspace JSON (No Secrets)');
    expBtn.onclick = async () => {
      await window.soraDesktop?.exportJson();
    };
    const keyInput = el('input') as HTMLInputElement;
    keyInput.type = 'password';
    keyInput.placeholder = isAr ? 'مفتاح Gemini (محلي مشفر بالخزنة)' : 'Gemini API Key (Encrypted in Desktop Vault)';
    const saveKeyBtn = el('button', isAr ? 'حفظ المفتاح المشفر' : 'Save Encrypted Key');
    saveKeyBtn.onclick = async () => {
      await window.soraDesktop?.setSecret('gemini_api_key', keyInput.value);
      keyInput.value = '';
    };
    body.append(
      el('div', `Cloud Sync: ${syncOutput}`, 'muted'),
      syncBtn,
      expBtn,
      keyInput,
      saveKeyBtn
    );
  }

  shell.append(header, nav, body);
  root.append(shell);
}

async function init(): Promise<void> {
  if (window.soraDesktop) {
    state = await window.soraDesktop.loadState();
    selectedBookId = state.books[0]?.id ?? '';
  }
  render();
}

void init();
