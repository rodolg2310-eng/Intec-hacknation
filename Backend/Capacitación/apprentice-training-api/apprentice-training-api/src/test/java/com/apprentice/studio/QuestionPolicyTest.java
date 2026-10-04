package com.apprentice.studio;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class QuestionPolicyTest {
  @Test void sixTenMinuteWindowsDeliverThreeToFiveQuestions(){
    for(int window=0;window<6;window++){int delivered=0;long last=window*600-60;
      for(long seconds=window*600;seconds<(window+1)*600;seconds++){boolean pause=seconds%125==0;boolean speaking=!pause;
        if(QuestionPolicy.due(seconds,delivered,seconds-last,speaking,pause?1600:0,speaking?300000:0,true,false)){delivered++;last=seconds;}}
      assertTrue(delivered>=3&&delivered<=5,"Window "+window+" delivered "+delivered);
    }
  }
  @Test void continuousSpeechWaitsFourMinutesAndThenUsesAKeyMoment(){assertFalse(QuestionPolicy.due(240,0,60,true,0,239000,true,false));assertTrue(QuestionPolicy.due(241,0,60,true,0,240000,true,false));assertFalse(QuestionPolicy.due(241,0,60,true,0,240000,false,false));}
  @Test void deadlineCompletesMinimumEvenWithoutPauseOrNewKeyMoment(){assertTrue(QuestionPolicy.due(420,0,60,true,0,1000,false,true));assertTrue(QuestionPolicy.due(480,1,60,true,0,1000,false,true));assertTrue(QuestionPolicy.due(540,2,60,true,0,1000,false,true));assertFalse(QuestionPolicy.due(570,5,60,false,2000,0,true,false));}
  @Test void pendingQuestionsAndShortPausesDoNotTriggerDuplicates(){assertFalse(QuestionPolicy.due(180,1,60,false,2000,0,true,true));assertFalse(QuestionPolicy.due(180,1,60,false,1499,0,true,false));assertFalse(QuestionPolicy.due(180,1,10,false,2000,0,true,false));}
}
