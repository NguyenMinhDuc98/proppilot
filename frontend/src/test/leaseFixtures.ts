import type { LeaseSource } from '../api/types'

/** A clause of the English lease of unit A-504 as a lease search returns it. */
export const lateFee: LeaseSource = {
  ref: 'LSE-A-504#5',
  documentCode: 'LSE-A-504',
  documentTitle: 'Residential lease agreement, unit A-504',
  unitCode: 'A-504',
  tenantNameEn: 'Khalid Al-Qahtani',
  tenantNameAr: 'خالد القحطاني',
  language: 'en',
  sectionNo: 5,
  sectionTitle: 'Late payment',
  page: 2,
  text: '5. Late payment\nRent not received within 5 days of its due date carries a late fee of 3% of the monthly rent, SAR 150.00 for each month.',
  pdfPath: '/api/lease-documents/LSE-A-504/pdf',
}

/** The same lease's notice clause, split in two parts, the first of which is cited as #4a. */
export const noticePart: LeaseSource = {
  ...lateFee,
  ref: 'LSE-A-504#4a',
  sectionNo: 4,
  sectionTitle: 'Notice period',
  page: 1,
  text: '4. Notice period\nEither party who wishes to end the lease at the end of its term must give written notice at least 60 days before.',
}

export const preamble: LeaseSource = {
  ...lateFee,
  ref: 'LSE-A-504#0',
  sectionNo: null,
  sectionTitle: null,
  page: 1,
  text: 'This lease is made between the landlord and the tenant of unit A-504.',
}

/** A clause of the Arabic lease of unit A-203, which is written right to left. */
export const arabicLateFee: LeaseSource = {
  ref: 'LSE-A-203#5',
  documentCode: 'LSE-A-203',
  documentTitle: 'عقد إيجار سكني – الوحدة A-203',
  unitCode: 'A-203',
  tenantNameEn: 'Ahmed Al-Otaibi',
  tenantNameAr: 'أحمد العتيبي',
  language: 'ar',
  sectionNo: 5,
  sectionTitle: 'غرامة التأخير',
  page: 3,
  text: 'البند 5: غرامة التأخير\nإذا لم يصل الإيجار خلال 5 أيام من تاريخ استحقاقه تستحق غرامة قدرها 3 بالمائة، أي 144 ريال سعودي.',
  pdfPath: '/api/lease-documents/LSE-A-203/pdf',
}
