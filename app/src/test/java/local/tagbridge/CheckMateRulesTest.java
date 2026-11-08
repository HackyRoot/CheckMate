package local.tagbridge;
import org.junit.Test;
import static org.junit.Assert.*;

public class CheckMateRulesTest {
 @Test public void qualityVariantsShareChannelIdentity(){
  assertEquals(LiveAddonServer.normalizeChannelName("Hungama"),LiveAddonServer.normalizeChannelName("Hungama TV HD (576p)"));
 }
 @Test public void distinctFeedsStaySeparate(){
  assertNotEquals(LiveAddonServer.normalizeChannelName("Hungama"),LiveAddonServer.normalizeChannelName("Super Hungama"));
  assertNotEquals(LiveAddonServer.normalizeChannelName("News Hindi"),LiveAddonServer.normalizeChannelName("News English"));
  assertNotEquals(LiveAddonServer.normalizeChannelName("News (US)"),LiveAddonServer.normalizeChannelName("News (IN)"));
 }
 @Test public void higherAdvertisedQualityRanksFirst(){
  assertTrue(LiveStreamChecker.qualityFromText("4K UHD")>LiveStreamChecker.qualityFromText("1080p"));
  assertTrue(LiveStreamChecker.qualityFromText("1080p")>LiveStreamChecker.qualityFromText("576p"));
 }
 @Test public void manifestQuerySurvivesResourceRouting(){
  String base=CheckMateConfig.providerBase("https://provider.example/profile/manifest.json?key=example");
  assertEquals("https://provider.example/profile/stream/series/test.json?key=example",CheckMateConfig.providerResource(base,"stream/series/test.json"));
 }
 @Test public void nuvioQueryConfigurationIsNotDropped(){
  assertEquals("https://provider.example/profile/manifest.json?key=example",CheckMateConfig.manifestFromBase("https://provider.example/profile?key=example"));
 }
 @Test public void tagIsOptionalAndValidated(){
  assertEquals("",CheckMateConfig.normalizeTag(""));
  assertEquals("AA:BB:CC:DD:EE:FF",CheckMateConfig.normalizeTag("aa:bb:cc:dd:ee:ff"));
  assertThrows(IllegalArgumentException.class,()->CheckMateConfig.normalizeTag("not-a-tag"));
 }
}
