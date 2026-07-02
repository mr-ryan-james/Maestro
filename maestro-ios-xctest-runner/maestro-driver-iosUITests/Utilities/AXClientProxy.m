#import "AXClientProxy.h"
#import "XCAccessibilityElement.h"
#import "XCUIDevice.h"
#import "XCAXClient_iOS.h"

static id AXClient = nil;

@implementation AXClientProxy

+ (instancetype)sharedClient
{
    static AXClientProxy *instance = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        instance = [[self alloc] init];
        AXClient = [XCUIDevice.sharedDevice accessibilityInterface];
    });
    return instance;
}

- (NSArray<id<XCAccessibilityElement>> *)activeApplications
{
    return [AXClient activeApplications];
}

- (NSDictionary *)defaultParameters {
    return [AXClient defaultParameters];
}

- (BOOL)setAXTimeoutSeconds:(double)seconds error:(NSError **)error {
    XCAXClient_iOS *client = (XCAXClient_iOS *)AXClient;
    if (![client respondsToSelector:@selector(_setAXTimeout:error:)]) {
        if (error) {
            *error = [NSError errorWithDomain:@"maestro.ax"
                                         code:-1
                                     userInfo:@{NSLocalizedDescriptionKey: @"_setAXTimeout:error: unavailable on this XCTest version"}];
        }
        return NO;
    }
    return [client _setAXTimeout:seconds error:error];
}

@end
