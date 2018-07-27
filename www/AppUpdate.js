var exec = require('cordova/exec');

exports.checkUpdate = function (success, error) {
    exec(success, error, 'AppUpdate', 'checkUpdate', []);
};
