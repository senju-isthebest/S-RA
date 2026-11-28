export const DESKTOP_STRINGS = {
  en: {
    appName: 'SŌRA Desktop',
    tagline: 'Windows Learning Workspace & Offline AI Study Engine',
    tabLibrary: 'Library & Upload',
    tabReader: 'Reader & Full-Book Q&A',
    tabExams: 'Exam Engine',
    tabFlashcards: 'Flashcards & Notes',
    tabSecurity: 'Sync & Security',
    openBookFile: 'Open Textbook File (.txt / .md / .pdf)',
    importPaste: 'Import Study Text',
    askBook: 'Ask Across Whole Textbook',
    syncNow: 'Sync Now',
    privacyMode: 'Local-Only Privacy Mode',
    exportJson: 'Export Workspace JSON (No Secrets)',
    saveKey: 'Save Encrypted Gemini Key',
    hasKeySet: 'Encrypted Key Stored in Vault',
    noKeySet: 'No Gemini Key Stored'
  },
  ar: {
    appName: 'سُورا لسطح المكتب',
    tagline: 'بيئة التعلم الذكية لنظام ويندوز بدون اتصال',
    tabLibrary: 'المكتبة والرفع',
    tabReader: 'القارئ والأسئلة الشاملة',
    tabExams: 'محرك الاختبارات',
    tabFlashcards: 'البطاقات والملاحظات',
    tabSecurity: 'المزامنة والأمان',
    openBookFile: 'فتح ملف كتاب (.txt / .md / .pdf)',
    importPaste: 'استيراد نص دراسي',
    askBook: 'اسأل عبر كامل الكتاب',
    syncNow: 'مزامنة الآن',
    privacyMode: 'وضع الخصوصية المحلي فقط',
    exportJson: 'تصدير بيانات الدراسة JSON (دون أسرار)',
    saveKey: 'حفظ مفتاح Gemini المشفر',
    hasKeySet: 'المفتاح محفوظ ومشفر في الخزنة',
    noKeySet: 'لا يوجد مفتاح Gemini محفوظ'
  }
} as const;

export type DesktopStringKey = keyof typeof DESKTOP_STRINGS.en;
