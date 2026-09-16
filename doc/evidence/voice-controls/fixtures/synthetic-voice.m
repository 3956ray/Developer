#import <Foundation/Foundation.h>
#import <AVFoundation/AVFoundation.h>
#include <unistd.h>
#include <math.h>
int main(void) { @autoreleasepool {
    NSData *input=[[NSFileHandle fileHandleWithStandardInput] readDataToEndOfFile];
    if(input.length>8192) return 2;
    NSString *text=[[NSString alloc] initWithData:input encoding:NSUTF8StringEncoding];
    AVSpeechSynthesisVoice *voice=nil;
    for(AVSpeechSynthesisVoice *v in AVSpeechSynthesisVoice.speechVoices) if([v.identifier isEqualToString:@"com.apple.voice.compact.zh-CN.Tingting"]) { voice=v;break; }
    if(!voice) return 3;
    fprintf(stderr,"voice=%s language=%s\n",voice.identifier.UTF8String,voice.language.UTF8String);
    AVSpeechSynthesizer *synth=[AVSpeechSynthesizer new];
    AVSpeechUtterance *utterance=[AVSpeechUtterance speechUtteranceWithString:text];utterance.voice=voice;utterance.rate=0.45;
    __block NSMutableData *floats=[NSMutableData new];__block double rate=0;__block BOOL done=NO;__block BOOL failed=NO;
    [synth writeUtterance:utterance toBufferCallback:^(AVAudioBuffer *raw) {
        if(![raw isKindOfClass:AVAudioPCMBuffer.class]) { failed=YES;done=YES;return; }
        AVAudioPCMBuffer *buffer=(AVAudioPCMBuffer *)raw;
        if(buffer.frameLength==0) {
            if(rate<=0) { failed=YES;done=YES;return; }
            NSUInteger count=floats.length/sizeof(float),length=(NSUInteger)(count*16000/rate);
            if(length>16000*90) { failed=YES;done=YES;return; }
            const float *source=floats.bytes;NSMutableData *pcm=[NSMutableData dataWithLength:length*2];int16_t *dest=pcm.mutableBytes;
            for(NSUInteger i=0;i<length;i++) { double at=i*rate/16000;NSUInteger left=(NSUInteger)at,right=MIN(left+1,count-1);float value=source[left]+(at-left)*(source[right]-source[left]);dest[i]=(int16_t)MAX(-32768,MIN(32767,value*32767)); }
            [[NSFileHandle fileHandleWithStandardOutput] writeData:pcm];
            memset(floats.mutableBytes,0,floats.length);memset(pcm.mutableBytes,0,pcm.length);done=YES;return;
        }
        rate=buffer.format.sampleRate;
        if(!buffer.floatChannelData || floats.length+buffer.frameLength*4>rate*90*4) { failed=YES;done=YES;return; }
        [floats appendBytes:buffer.floatChannelData[0] length:buffer.frameLength*4];
    }];
    NSDate *deadline=[NSDate dateWithTimeIntervalSinceNow:120];
    while(!done && deadline.timeIntervalSinceNow>0) [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.05]];
    return done && !failed ? 0 : 4;
} }
