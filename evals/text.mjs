// How the eval grading reads text. Kept apart so every module compares answers the same way.

/** Arabic marks that do not change a word: short vowels and the like (U+064B to U+065F, U+0670) and tatweel. */
const ARABIC_MARKS = /[ً-ٰٟـ]/g

/**
 * Lower case, and without number formatting: 4,800 = 4800 = ٤٨٠٠ = ٤٬٨٠٠, because models format numbers freely. A
 * comma or a space that stands in for a comma (no-break space, narrow no-break space) between a digit and a group of
 * three digits is a thousands separator; one before any other number of digits is not.
 */
export function normalize(text) {
  return String(text)
    .toLowerCase()
    .replace(/[٠-٩]/g, (digit) => String(digit.charCodeAt(0) - 0x0660))
    .replace(/(\d)[,٬  ](?=\d{3}(?!\d))/g, '$1')
}

/**
 * The text with one spelling for what is written in more than one way: apostrophes straight, and Arabic without its
 * marks, with the forms of alef (أ إ آ) as one, ى as ي and ة as ه. Case and number formatting are normalize's.
 */
export function unifySpelling(text) {
  return String(text)
    .replace(/[‘’ʼ]/g, "'")
    .replace(ARABIC_MARKS, '')
    .replace(/[أإآٱ]/g, 'ا')
    .replace(/ى/g, 'ي')
    .replace(/ة/g, 'ه')
}

/**
 * The text as every comparison of the grading reads it: normalised as above and with one spelling of each word, so a
 * fact, a marker word or a name matches however an answer typed it.
 */
export function fold(text) {
  return unifySpelling(normalize(text))
}

/** Whether the folded text holds any of the folded words. */
export function mentionsAny(text, words) {
  const folded = fold(text)
  return words.some((word) => folded.includes(fold(word)))
}

/** A unit code or an identifier as it is compared: lower case, with no hyphen or white space. */
export function compact(text) {
  return normalize(text).replace(/[\s-]+/g, '')
}
