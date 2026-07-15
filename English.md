# [English](English.md) [中文](README.md)

> Store links using an `io.legado.*` package name refer to upstream or historical distributions, not the personal build produced by this checkout. The current Release applicationId is `shutiao.reader.release`; those external store pages were not checked online in this documentation pass.

[![icon_android](https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/icon_android.png)](https://play.google.com/store/apps/details?id=io.legado.play.release)
<a href="https://jb.gg/OpenSourceSupport" target="_blank">
<img width="24" height="24" src="https://resources.jetbrains.com/storage/products/company/brand/logos/jb_beam.svg?_gl=1*135yekd*_ga*OTY4Mjg4NDYzLjE2Mzk0NTE3MzQ.*_ga_9J976DJZ68*MTY2OTE2MzM5Ny4xMy4wLjE2NjkxNjMzOTcuNjAuMC4w&_ga=2.257292110.451256242.1669085120-968288463.1639451734" alt="idea"/>
</a>
<div align="center">
<img width="125" height="125" src="https://github.com/hupohupochuan/legado/raw/master/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="legado"/>
  
Legado / 开源阅读
<br>
<a href="https://gedoor.github.io" target="_blank">gedoor.github.io</a> / <a href="https://www.legado.top/" target="_blank">legado.top</a>
<br>
Legado is a free and open source novel reader for Android.
</div>

[![](https://img.shields.io/badge/-Contents:-696969.svg)](#contents) [![](https://img.shields.io/badge/-Function-F5F5F5.svg)](#Function-) [![](https://img.shields.io/badge/-Download-F5F5F5.svg)](#Download-) [![](https://img.shields.io/badge/-Community-F5F5F5.svg)](#Community-) [![](https://img.shields.io/badge/-API-F5F5F5.svg)](#API-) [![](https://img.shields.io/badge/-Other-F5F5F5.svg)](#Other-) [![](https://img.shields.io/badge/-Grateful-F5F5F5.svg)](#Grateful-) [![](https://img.shields.io/badge/-Interface-F5F5F5.svg)](#Interface-)

>New user?
>
>The software does not provide content, you need to add it manually, such as importing book sources, etc. 
>Take a look at [official help documentation](https://www.yuque.com/legado/wiki)，Maybe there's an answer you need inside.

# Function [![](https://img.shields.io/badge/-Function-F5F5F5.svg)](#Function-)

* Define custom ebook sources and rules for search, discovery, book details, tables of contents, and chapter content.
* Use RSS-type book sources (`bookSourceType=5`) for subscription-style content.
* Switch between list and grid bookshelves and schedule checks for new chapters.
* Read TXT, EPUB, UMD, PDF, MOBI, AZW/AZW3, and CBZ files; supported books can also be imported from ZIP, RAR, or 7Z containers.
* Apply replacement rules to clean or rewrite chapter text.
* Customize fonts, colors, backgrounds, spacing, bold text, simplified/traditional conversion, and page-turn modes.
* Use system TTS, HTTP TTS, or audio-book playback.
* Back up and restore data locally or through WebDAV, including reading-progress synchronization.
* Use the built-in LAN Web UI for bookshelf, reading, book-source, and replacement-rule management.
* Open-source and ad-free.


<a href="#readme">
    <img src="https://img.shields.io/badge/-Top-orange.svg" alt="#" align="right">
</a>

# Download [![](https://img.shields.io/badge/-Download-F5F5F5.svg)](#Download-)

#### Android

* [Releases](https://github.com/hupohupochuan/legado/releases/latest)
* [Google play - $1.99](https://play.google.com/store/apps/details?id=io.legado.play.release)
* [Coolapk](https://www.coolapk.com/apk/io.legado.app.release)
* [\#Beta](https://kunfei.lanzoui.com/b0f810h4b)
* [IzzyOnDroid F-Droid Repository](https://apt.izzysoft.de/fdroid/index/apk/io.legado.app.release)


#### IOS

* Stopped(No release) - [Github](https://github.com/gedoor/YueDuFlutter)

<a href="#readme">
    <img src="https://img.shields.io/badge/-Top-orange.svg" alt="#" align="right">
</a>

# Community [![](https://img.shields.io/badge/-Community-F5F5F5.svg)](#Community-)

#### Telegram

[![Telegram-group](https://img.shields.io/badge/Telegram-group-blue)](https://t.me/yueduguanfang) [![Telegram-channel](https://img.shields.io/badge/Telegram-channel-blue)](https://t.me/legado_channels)

#### Discord

[![Discord](https://img.shields.io/discord/560731361414086666?color=%235865f2&label=Discord)](https://discord.gg/VtUfRyzRXn)

#### Other

https://www.yuque.com/legado/wiki/community

<a href="#readme">
    <img src="https://img.shields.io/badge/-Top-orange.svg" alt="#" align="right">
</a>

# API [![](https://img.shields.io/badge/-API-F5F5F5.svg)](#API-)

* Legado 3.0 provides both a Web API and a Content Provider API. The Content Provider is available only to clients that request the matching permission and are signed with the same certificate; see the [API documentation](api.md).
* One-click import by url recall reading, url format: legado://import/{path}?src={url}
* Path Type: bookSource,rssSource,replaceRule,textTocRule,httpTTS,theme,readConfig,dictRule,addToBookshelf
* Path type explanation: book source, compatibility alias for an RSS-type book source (`bookSourceType=5`), replacement rule, local TXT table-of-contents rule, online TTS engine, theme, reading layout, dictionary rule, [add to bookshelf](/app/src/main/java/io/legado/app/ui/association/AddToBookshelfDialog.kt)

<a href="#readme">
    <img src="https://img.shields.io/badge/-Top-orange.svg" alt="#" align="right">
</a>

# Other [![](https://img.shields.io/badge/-Other-F5F5F5.svg)](#Other-)

##### Disclaimers

https://gedoor.github.io/Disclaimer

##### Legado 3.0

* [eBook sources rules](https://mgz0227.github.io/The-tutorial-of-Legado/)
* [Update Log](/app/src/main/assets/updateLog.md)
* [Help Documentation](/app/src/main/assets/web/help/md/appHelp.md)
* [Built-in Web UI (bookshelf, reader, sources, and replacement rules)](/modules/web/README.md)

##### Support Development

If this project helps you, you can voluntarily support ongoing maintenance via PayPal.

<img src="docs/assets/paypal-qr.jpg" alt="PayPal QR Code" width="180">

Sponsorship does not unlock any features, content, book sources, or services.

<a href="#readme">
    <img src="https://img.shields.io/badge/-Top-orange.svg" alt="#" align="right">
</a>

# Grateful [![](https://img.shields.io/badge/-Grateful-F5F5F5.svg)](#Grateful-)

> * org.jsoup:jsoup
> * com.jayway.jsonpath:json-path
> * org.mozilla:rhino
> * com.squareup.okhttp3:okhttp
> * com.github.bumptech.glide:glide
> * org.nanohttpd:nanohttpd
> * org.nanohttpd:nanohttpd-websocket
> * com.jaredrummler:colorpicker
> * io.noties.markwon:core
> * io.noties.markwon:image-glide
> * me.zhanghai.android.libarchive:library
> * com.github.liuyueyi.quick-chinese-transfer:quick-transfer-core
> * epublib (vendored under `modules/book`)

<a href="#readme">
    <img src="https://img.shields.io/badge/-Top-orange.svg" alt="#" align="right">
</a>

# Interface [![](https://img.shields.io/badge/-Interface-F5F5F5.svg)](#Interface-)

<img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B1.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B2.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B3.jpg" width="270">
<img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B4.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B5.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B6.jpg" width="270">

<a href="#readme">
    <img src="https://img.shields.io/badge/-Top-orange.svg" alt="#" align="right">
</a>
