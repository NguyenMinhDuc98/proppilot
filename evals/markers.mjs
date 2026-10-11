// The words that tell a decline from an answer, and a question from a statement, in each language of the cases.
// They are matched on folded text (see fold in text.mjs), so spelling variants of Arabic and typographic apostrophes
// match. They are a floor, not a judgement: the LLM judge reads the same answers for tone when it runs.

import { mentionsAny } from './text.mjs'

const DECLINES = {
  en: [
    "can't", 'cannot', 'can not', 'unable to', 'not able to', "i'm not able", 'i am not able', 'not something i',
    "isn't something", 'is not something', 'outside', 'beyond', 'out of scope', 'not within', "i don't have",
    'i do not have', "i won't", 'i will not', 'not permitted', 'not allowed', 'not designed', 'only help',
    'only able', 'sorry', 'apolog', 'unfortunately', 'did not understand', "didn't understand",
    'do not understand', "don't understand",
  ],
  ar: [
    'لا أستطيع', 'لا يمكنني', 'لا يمكن', 'لا أقدر', 'غير قادر', 'ليس بإمكاني', 'لا أتمكن', 'لا يسعني', 'عذرا',
    'أعتذر', 'آسف', 'خارج نطاق', 'خارج اختصاص', 'خارج صلاحيات', 'ليس من صلاحياتي', 'لا أملك', 'لا أقدم', 'لا أدعم',
    'لا تتوفر لدي', 'لم أفهم', 'لا أفهم', 'للأسف',
  ],
}

const ASKS = {
  en: [
    '?', 'which one', 'which of', 'which tenant', 'which unit', 'which do you mean', 'who do you mean', 'do you mean',
    'did you mean', 'please specify', 'please confirm', 'please clarify', 'could you clarify', 'can you clarify',
    'let me know which', 'tell me which', 'specify which', 'provide the unit', 'give me the unit', 'unit code',
  ],
  ar: [
    '؟', '?', 'أي منهم', 'أيهم', 'أي واحد', 'حدد', 'يرجى تحديد', 'يرجى توضيح', 'من تقصد', 'هل تقصد', 'رمز الوحدة',
    'رقم الوحدة', 'أخبرني', 'زودني',
  ],
}

/** Whether the answer says it will not or cannot do what it was asked, in the language of the case. */
export function declines(answer, lang) {
  return mentionsAny(answer, DECLINES[lang] ?? DECLINES.en)
}

/** Whether the answer puts a question to the user, or tells them to say which one. */
export function asksWhich(answer, lang) {
  return mentionsAny(answer, ASKS[lang] ?? ASKS.en)
}
