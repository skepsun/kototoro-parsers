var SinConf = function () {
    return {
        edition: 3,
        mainHost: {"desktop":"https://www.92mh.com","phone":"https://h5.92mh.com"},
        apiHost: 'http://api.92mh.com',
        resHost: [{"name":"自动选择","domain":["https://cover.alltucdn.cc"]},{"name":"电信线路","domain":["https://cover.alltucdn.cc"]}],
        scheduler: "off",
        theme: {"basic":"ykmh","dmzj":{"background":{"mode":"rand","images":["01.png","02.png"]}}},
        common: [],
        desktop: {"chapter":{"mode":"pagination","reload":true,"maxPreload":10,"imageWidth":"auto"}},
        phone: {"chapter":{"mode":"pagination","reload":true,"maxPreload":10,"imageWidth":"auto"}},
        hotKeys: {"back":"backspace","read":"r","next":"right","prev":"left","nextChapter":"x","prevChapter":"z"},
        comicDiscuss: {"maxContentLent":1000,"minContentLent":3,"censor":false},
        toastPosition: 'toast-top-center',
        toastClose: true,
        init: function () {
        }
    };
}();

toastr.options.positionClass = SinConf.toastPosition;
toastr.options.closeButton = SinConf.toastClose;
