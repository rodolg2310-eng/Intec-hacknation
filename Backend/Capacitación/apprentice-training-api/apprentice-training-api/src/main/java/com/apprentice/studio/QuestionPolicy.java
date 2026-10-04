package com.apprentice.studio;
/** Uses active recording time, never wall time or the number of drafts. */
public final class QuestionPolicy {
  private QuestionPolicy(){}
  public static boolean forced(long seconds,int delivered){return delivered<3 && seconds%600>=420+delivered*60;}
  public static boolean due(long seconds,int delivered,long sinceLast,boolean speaking,long idleMs,long continuousMs,boolean keyMoment,boolean pending){
    if(seconds<60||seconds>3600||delivered>=5||sinceLast<45)return false;
    if(forced(seconds,delivered))return true;
    if(pending)return false;
    return (!speaking&&idleMs>=1500&&keyMoment)||(continuousMs>=240000&&keyMoment);
  }
}
