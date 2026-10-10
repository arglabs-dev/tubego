package dev.arglabs.tubego;
import android.app.Activity;import android.os.Bundle;import android.content.Context;
/** Each screen rebuilds its labels on returning after an account language change. */
public class LocalizedActivity extends Activity {
 private String screenLanguage;private Context textContext;
 @Override protected void onCreate(Bundle state){screenLanguage=LanguagePreferences.language(this,LanguagePreferences.origin(this));textContext=LanguagePreferences.localized(this,screenLanguage);super.onCreate(state);}
 @Override public void setContentView(android.view.View view){super.setContentView(UiKit.shell(this,view));}
 public final Context textContext(){return textContext==null?this:textContext;}
 @Override protected void onResume(){super.onResume();String current=LanguagePreferences.language(this,LanguagePreferences.origin(this));if(screenLanguage!=null&&!screenLanguage.equals(current)&&!isFinishing())recreate();}
}
