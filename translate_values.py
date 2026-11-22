# -*- coding: utf-8 -*-
# Translate the remaining values XML files to Chinese via a phrase dictionary.
# Keeps the same <string name=...> keys, placeholders (%1$s etc) and XML structure.
import re, os

# exact full-value translations (value after XML unescape)
D = {
 # details
 "Preparing…":"准备中…","Start over":"从头播放","More":"更多","Cancel":"取消","Sources":"片源","Cached":"已缓存","Watched":"已看","Mark watched":"标记已看","In my list":"在片单中","My list":"我的片单","Following":"已关注","Follow":"关注","1 season":"1 季","Finding sources…":"正在查找片源…","No Spanish sources available for this profile":"此档案没有西语片源","already in your Real-Debrid":"已在你的 Real-Debrid 中","Episodes":"剧集","Loading episodes…":"加载剧集中…","Searching…":"搜索中…","Choose a source":"选择片源","Pick an episode first":"请先选择一集","No sources":"暂无片源","None in Spanish":"无西语片源","Spanish only":"仅西语","In your Real-Debrid":"在你的 Real-Debrid 中","Blocked by Real-Debrid":"已被 Real-Debrid 屏蔽","No title selected":"未选择影片","No seasons available":"暂无季","No sources available for this right now":"当前暂无可用片源","Couldn't load sources: %1$s":"无法加载片源：%1$s","Coming soon: %1$s":"即将上线：%1$s","Resume%1$s":"继续%1$s","Play%1$s":"播放%1$s","S%1$d watched":"第%1$d季已看","Mark S%1$d watched":"标记第%1$d季已看","%1$s watched":"%1$s 已看","Mark %1$s watched":"标记 %1$s 已看","Mark as watched":"标记已看","Mark as unwatched":"标记为未看","Play episode":"播放本集","Hold OK on an episode for options":"长按剧集的 OK 查看选项","%1$s left":"剩余 %1$s","%1$d%% rating":"%1$d%% 评分","Rate movie":"给电影评分","Rate show":"给剧集评分","Rate episode %1$s":"给第 %1$s 集评分","Left/Right to choose · OK to save":"左右键选择 · OK 保存","%1$d / 10":"%1$d / 10","Remove rating":"移除评分","Saving…":"保存中…","Couldn't save the rating. Try again.":"无法保存评分，请重试。","Couldn't load Trakt ratings.":"无法加载 Trakt 评分。","Connect Trakt in your profile settings to rate titles.":"在档案设置中连接 Trakt 即可评分。","Retry":"重试","%1$d seasons":"%1$d 季"," for %1$s":" · %1$s","No sources%1$s":"暂无片源%1$s","cached on %1$s":"缓存于 %1$s","%1$d sources%2$s · %3$s %4$s ⚡":"%1$d 个片源%2$s · %3$s %4$s ⚡","%1$d sources%2$s":"%1$d 个片源%2$s","Season %1$d":"第 %1$d 季","%1$d / %2$d episodes watched":"已看 %1$d / %2$d 集","%1$d/%2$d watched":"已看 %1$d/%2$d","%1$d sources":"%1$d 个片源","%1$d shown · %2$d total":"显示 %1$d · 共 %2$d","Show all":"全部显示","Apply exclusions":"应用排除项","No sources match your exclusion settings":"没有符合排除设置的片源","Trailer":"预告片","Cast &amp; crew":"演员与幕后","%1$s":"%1$s","Directed by %1$s":"导演：%1$s","Created by %1$s":"出品：%1$s","Random episode":"随机剧集","Couldn't load this person.":"无法加载此影人。","Movies":"电影","Series":"电视剧","No titles available.":"暂无作品。","Actor/actress":"演员","Director":"导演","Writer":"编剧","Producer":"制片人","Mark source as broken":"标记片源不可用","Source discarded":"已丢弃该片源","Hold OK on a source to discard it":"长按片源的 OK 可丢弃","Creator":"创作者","Episode %1$s":"第 %1$s 集","Show":"剧集","▶ PLAYS WHEN YOU PRESS PLAY":"▶ 按播放键即播放","That source doesn't work: %1$s Pick another one.":"该片源不可用：%1$s 请换一个。",
 # player
 "Player":"播放器","Playing":"播放中","Buffering…":"缓冲中…","Paused":"已暂停","Ended":"已结束","Next episode":"下一集","Playing next in":"即将播放下一集","Skip intro":"跳过片头","Audio":"音轨","Subtitles":"字幕","Speed":"倍速","Quality":"清晰度","Fullscreen":"全屏","Picture-in-picture":"画中画","Frame rate":"帧率","Matching frame rate":"匹配帧率","Sleep timer":"睡眠定时","Off":"关闭","Resume":"继续","Start from beginning":"从头播放","Source":"片源","Switch source":"切换片源","No audio track":"无音轨","Direct audio":"音频直通","Subtitle sync":"字幕同步","Subtitle size":"字幕大小","Subtitle position":"字幕位置","Reset":"重置","Live":"直播","Searching subtitles…":"搜索字幕…","No subtitles found":"未找到字幕","Downloading…":"下载中…","Playback error":"播放错误","Can't play this source":"无法播放该片源","Try another source":"请换一个片源","Rebuffering":"重新缓冲","Loading":"加载中","Stopped":"已停止","Restart":"重新播放","Keep watching":"继续观看","Watched":"已看","Mark watched":"标记已看","Next":"下一个","Previous":"上一个","OK":"确定","Back":"返回","Exit":"退出","Exit player?":"退出播放器？","Hold OK to exit":"长按 OK 退出","Now playing":"正在播放","Episode %1$d":"第 %1$d 集","Season %1$d":"第 %1$d 季","%1$d%%":"%1$d%%","%1$x":"%1$x",
 # domain / common
 "Movie":"电影","Show":"剧集","TV":"电视","Unknown":"未知","All":"全部","None":"无","Yes":"是","No":"否","Name":"名称","Type":"类型","Size":"大小","Language":"语言","Duration":"时长","Year":"年份","Rating":"评分","Genre":"类型","Description":"简介","Settings":"设置","Cancel":"取消","Save":"保存","Delete":"删除","Edit":"编辑","Add":"添加","Remove":"移除","OK":"确定","Done":"完成","Close":"关闭","Open":"打开","Back":"返回","Next":"下一步","Previous":"上一步","Retry":"重试","Loading…":"加载中…","Error":"错误","Success":"成功","Warning":"警告","Info":"信息","Default":"默认","Automatic":"自动","Manual":"手动","Enabled":"已启用","Disabled":"已禁用","On":"开","Off":"关","Search":"搜索","Home":"首页","Download":"下载","Downloads":"下载管理","Update":"更新","Check for updates":"检查更新","Version":"版本","About":"关于","Help":"帮助","Exit":"退出","Profile":"档案","Profiles":"档案","Add profile":"添加档案","Edit profile":"编辑档案","Delete profile":"删除档案","Kids":"儿童","Main":"主档案","Switch profile":"切换档案",
}

# xml entity decode/encode helpers
def dec(v):
    return v.replace('&amp;','&').replace('&lt;','<').replace('&gt;','>').replace('&quot;','"').replace("&apos;","'")
def enc(v):
    return v.replace('&','&amp;').replace('<','&lt;').replace('>','&gt;').replace('"','&quot;')

files = [
 'core/strings/src/main/res/values/details.xml',
 'core/strings/src/main/res/values/player.xml',
 'core/strings/src/main/res/values/domain.xml',
 'core/strings/src/main/res/values/downloads.xml',
 'core/strings/src/main/res/values/settings.xml',
]
total = 0
for p in files:
    if not os.path.exists(p): continue
    s = open(p, encoding='utf-8').read()
    def repl(m):
        nonlocal_dummy = None
        name = m.group(1); raw = m.group(2)
        v = dec(raw)
        if v in D:
            return '<string name="%s">%s</string>' % (name, enc(D[v]))
        return m.group(0)
    s2 = re.sub(r'<string name="([^"]+)">(.*?)</string>', repl, s, flags=re.S)
    n = sum(1 for m in re.finditer(r'<string name="([^"]+)">(.*?)</string>', s, re.S) if dec(m.group(2)) in D)
    total += n
    open(p,'w',encoding='utf-8').write(s2)
    print(os.path.basename(p), 'translated', n)
print('TOTAL', total)
