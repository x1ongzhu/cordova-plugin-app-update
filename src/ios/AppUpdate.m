/********* AppUpdate.m Cordova Plugin Implementation *******/

#import <Cordova/CDV.h>

#define IOS10_OR_LATER  ([[NSProcessInfo processInfo] isOperatingSystemAtLeastVersion:(NSOperatingSystemVersion){.majorVersion = 10, .minorVersion = 0, .patchVersion = 0}])

@interface AppUpdate : CDVPlugin {
    // Member variables go here.

}
@property(nonatomic, strong) NSString *updateUrl;
@property(nonatomic, assign) BOOL checkAgainOnResume;

- (void)checkUpdate:(CDVInvokedUrlCommand *)command;
@end

@implementation AppUpdate
- (void)pluginInitialize {
    [[NSNotificationCenter defaultCenter]
            addObserver:self
               selector:@selector(onResume)
                   name:UIApplicationDidBecomeActiveNotification object:nil];
    self.updateUrl = self.commandDelegate.settings[[@"CheckUpdateUrl" lowercaseString]];
    id CheckUpdateOnLoadValue = self.commandDelegate.settings[[@"CheckUpdateOnLoad" lowercaseString]];
    BOOL checkUpdateOnLoad = CheckUpdateOnLoadValue == nil ? false : [CheckUpdateOnLoadValue boolValue];
    if (checkUpdateOnLoad) {
        [self checkUpdate:nil];
    }
}

- (void)checkUpdate:(CDVInvokedUrlCommand *)command {

    // 1.创建一个网络路径
    NSURL *url = [NSURL URLWithString:self.updateUrl];
    NSURLRequest *request = [NSURLRequest requestWithURL:url];
    NSURLSession *session = [NSURLSession sharedSession];
    NSURLSessionDataTask *sessionDataTask = [session dataTaskWithRequest:request completionHandler:^(NSData *_Nullable data, NSURLResponse *_Nullable response, NSError *_Nullable error) {
        NSLog(@"从服务器获取到数据");

        NSDictionary *dict = [NSJSONSerialization JSONObjectWithData:data options:(NSJSONReadingMutableLeaves) error:nil];
        NSLog([NSString stringWithFormat:@"%@", dict]);
        if ([dict[@"success"] boolValue]) {
            NSDictionary *iosForce = dict[@"data"][@"iosForce"];
            if ([self versionLowerThan:iosForce[@"hintVersion"]]) {
                self.checkAgainOnResume = true;
                [self showDialog:iosForce cancelable:false];
                return;
            }
            NSDictionary *ios = dict[@"data"][@"ios"];
            if ([self versionLowerThan:ios[@"hintVersion"]]) {
                NSDictionary *d = [[NSUserDefaults standardUserDefaults] objectForKey:@"checkUpdate"];
                if (d) {
                    if ([(NSString *) d[@"version"] isEqualToString:ios[@"updateVersionInfo"][@"version"]]) {
                        if ([[NSDate date] timeIntervalSince1970] - [d[@"time"] intValue] < 1 * 24 * 60 * 60) {
                            return;
                        }
                    }
                }
                [self showDialog:ios cancelable:true];
            }
        }
    }];
    [sessionDataTask resume];
    if (command != nil) {
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
    }
}

- (void)showDialog:(NSDictionary *)data cancelable:(BOOL)cancelable {
    UIAlertController *alert = [UIAlertController alertControllerWithTitle:@"版本更新" message:data[@"msg"] preferredStyle:UIAlertControllerStyleAlert];
    UIAlertAction *sure = [UIAlertAction actionWithTitle:@"立即更新" style:UIAlertActionStyleDefault handler:^(UIAlertAction *_Nonnull action) {
        NSURL *url = [NSURL URLWithString:data[@"updateVersionInfo"][@"url"]];

        if ([[UIApplication sharedApplication] canOpenURL:url]) {
            if (IOS10_OR_LATER) {
                [[UIApplication sharedApplication] openURL:url options:@{} completionHandler:^(BOOL success) {
                    NSLog(@"success = %d", success);
                }];
            } else {
                [[UIApplication sharedApplication] openURL:url];
            }
        }
    }];
    [alert addAction:sure];
    if (cancelable) {
        UIAlertAction *cancel = [UIAlertAction actionWithTitle:@"取消" style:UIAlertActionStyleCancel handler:^(UIAlertAction *_Nonnull action) {
            NSMutableDictionary *dic = [@{
                    @"version": data[@"updateVersionInfo"][@"version"],
                    @"time": @([[NSDate date] timeIntervalSince1970])
            } mutableCopy];
            [[NSUserDefaults standardUserDefaults] setObject:dic forKey:@"checkUpdate"];
        }];
        [alert addAction:cancel];
    }
    [self.viewController presentViewController:alert animated:YES completion:nil];
}

- (void)onResume {
    if (self.checkAgainOnResume) {
        [self checkUpdate:nil];
    }
}

- (int)getVersionValue:(NSString *)version {
    NSArray *arr = [version componentsSeparatedByString:@"."];
    int value = 0;
    for (int i = 0; i < arr.count; ++i) {
        value += pow(10, i) * [arr[arr.count - 1 - i] intValue];
    }
    return value;
}

- (BOOL)versionLowerThan:(NSString *)version {
    NSString *app_Version = [[NSBundle mainBundle] infoDictionary][@"CFBundleShortVersionString"];
    return [self getVersionValue:app_Version] <= [self getVersionValue:version];
}
@end
