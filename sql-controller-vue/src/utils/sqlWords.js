/* 静态词表：业务补充联想词
   CM6 的 @codemirror/lang-sql 已内置 SQL 关键字/函数/方言词表；
   此文件用于补充业务自定义词（如公司特有函数、规范缩写），
   通过 autocompletion({ override: [...] }) 注入，独立维护便于升级 */

export const BUSINESS_WORDS = [
  /* 业务字段规范缩写 */
  { label: 'create_by', detail: '创建人', type: 'keyword' },
  { label: 'create_time', detail: '创建时间', type: 'keyword' },
  { label: 'update_time', detail: '更新时间', type: 'keyword' },
  { label: 'delete_flag', detail: '删除标记', type: 'keyword' }
]

/* 自定义补全源：CM6 CompletionSource，前缀匹配业务词 */
export function businessCompletionSource(context) {
  const word = context.matchBefore(/\w*/)
  if (!word || (word.from === word.to && !context.explicit)) return null
  const prefix = word.text.toLowerCase()
  const options = BUSINESS_WORDS
    .filter(w => w.label.toLowerCase().startsWith(prefix))
    .map(w => ({ label: w.label, detail: w.detail, type: w.type }))
  return options.length ? { from: word.from, options } : null
}
