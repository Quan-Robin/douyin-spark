
// 与 MarkdownLite.java 完全相同的纯文本规则,用来预演真实说明的渲染效果
const fs = require('fs');
function headingLevel(l){let n=0;while(n<l.length&&l[n]==='#')n++;return (n>0&&n<=6&&n<l.length&&l[n]===' ')?n:0;}
function isTableSeparator(l){if(!l.startsWith('|'))return false;for(const c of l){if(c!=='|'&&c!=='-'&&c!==':'&&c!==' ')return false;}return l.includes('-');}
function tableRow(l){return l.split('|').map(s=>s.trim()).filter(s=>s.length).join(' · ');}
function inline(s){
  let out='',i=0;
  while(i<s.length){
    if(s.startsWith('**',i)){const e=s.indexOf('**',i+2);if(e>i+2){out+=s.slice(i+2,e);i=e+2;continue;}}
    const c=s[i];
    if(c==='\u0060'){const e=s.indexOf('\u0060',i+1);if(e>i+1){out+=s.slice(i+1,e);i=e+1;continue;}}
    if(c==='['){const cl=s.indexOf(']',i);const op=cl>0?s.indexOf('(',cl):-1;const ep=op>0?s.indexOf(')',op):-1;
      if(cl>i&&op===cl+1&&ep>op){out+=s.slice(i+1,cl);i=ep+1;continue;}}
    out+=c;i++;
  }
  return out;
}
function render(md){
  let text=md.replace(/\r\n/g,'\n').replace(/\r/g,'\n');
  if(text.length>6000)text=text.slice(0,6000)+'…';
  const lines=text.split('\n');
  let out='',prevBlank=true;
  for(let raw of lines){
    let line=raw.trim();
    if(isTableSeparator(line))continue;
    if(line.startsWith('|'))line=tableRow(line);
    const lv=headingLevel(line);
    if(lv>0)line=line.slice(lv).trim();
    if(/^[-*+] /.test(line))line='• '+line.slice(2).trim();
    if(line.startsWith('> '))line='▎'+line.slice(2);
    if(!line){ if(prevBlank)continue; out+='\n'; prevBlank=true; continue; }
    if(out.length>0&&!prevBlank)out+='\n';
    out+=inline(line);
    prevBlank=false;
  }
  return out;
}
const md = fs.readFileSync('D:/AI/DSH/docs/release-notes-v0.3.4.md','utf8');
const out = render(md);
console.log('原始 Markdown 长度:', md.length, '→ 渲染后:', out.length);
console.log('======= 手机上会看到的样子(前 40 行)=======');
console.log(out.split('\n').slice(0,40).join('\n'));
