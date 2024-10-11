import electronLogo from '@renderer/assets/electron.svg';

function Home(): JSX.Element {
  return (
    <>
      <img alt="logo" className="logo" src={electronLogo} />
      <div className="text">
        Build a <span className="react">Minecraft</span> Launcher with&nbsp;
        <span className="ts">React and TypeScript</span>
      </div>
      <p className="tip">
        Please try pressing <code>F12</code> to open the devTool
      </p>
    </>
  );
}

export default Home;
