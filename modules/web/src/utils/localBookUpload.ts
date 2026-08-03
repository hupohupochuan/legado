export const LOCAL_BOOK_EXTENSIONS = [
  'txt',
  'epub',
  'umd',
  'pdf',
  'mobi',
  'azw3',
  'azw',
  'cbz',
  'zip',
  'rar',
  '7z',
] as const

const localBookExtensionSet = new Set<string>(LOCAL_BOOK_EXTENSIONS)

export const LOCAL_BOOK_ACCEPT = LOCAL_BOOK_EXTENSIONS.map(
  extension => `.${extension}`,
).join(',')

export const isSupportedLocalBook = (fileName: string) => {
  const separatorIndex = fileName.lastIndexOf('.')
  if (separatorIndex < 0 || separatorIndex === fileName.length - 1) return false
  return localBookExtensionSet.has(
    fileName.slice(separatorIndex + 1).toLowerCase(),
  )
}
